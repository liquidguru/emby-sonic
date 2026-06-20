package guru.liquid.embysonic.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.SimilarAlbumDto
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.resumeStartItem
import guru.liquid.embysonic.data.playlist.PlaylistRepository
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackSource
import guru.liquid.embysonic.playback.playbackSourceFor
import kotlinx.coroutines.channels.Channel
import guru.liquid.embysonic.ui.nav.Routes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import javax.inject.Inject

/**
 * Backs a single drill-down level: an artist's albums, an album's tracks, an
 * author's books, or a book's chapters — selected by [DetailKind].
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val coordinator: CoordinatorApi,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>(Routes.ARG_ITEM_ID).orEmpty()
    val kind: DetailKind = runCatching {
        DetailKind.valueOf(savedStateHandle.get<String>(Routes.ARG_DETAIL_KIND).orEmpty())
    }.getOrDefault(DetailKind.ALBUM_TRACKS)
    val title: String = savedStateHandle.get<String>(Routes.ARG_TITLE).orEmpty()

    /** Card grid vs. list, persisted and shared across library/detail screens. */
    val listView: StateFlow<Boolean> =
        settings.libraryListView.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleListView() = viewModelScope.launch { settings.setLibraryListView(!listView.value) }

    private val _genreTracksPerMix = MutableStateFlow(DEFAULT_GENRE_MIX_TRACKS)
    val genreTracksPerMix: StateFlow<Int> = _genreTracksPerMix.asStateFlow()

    fun setGenreTracksPerMix(value: Int) {
        _genreTracksPerMix.value = value
        viewModelScope.launch { settings.setGeneratedMixTracks(value) }
    }

    private val _state = MutableStateFlow<TabState>(TabState.Loading)
    val state: StateFlow<TabState> = _state.asStateFlow()

    private val _similarState = MutableStateFlow<SimilarCollectionsState>(SimilarCollectionsState.Hidden)
    val similarState: StateFlow<SimilarCollectionsState> = _similarState.asStateFlow()

    init {
        observeGeneratedMixTracks()
        load()
    }

    private fun observeGeneratedMixTracks() {
        viewModelScope.launch {
            settings.generatedMixTracks.distinctUntilChanged().collect { count ->
                _genreTracksPerMix.value = count
                if (kind == DetailKind.GENRE_TRACKS) load()
            }
        }
    }

    fun load() {
        _state.value = TabState.Loading
        viewModelScope.launch {
            runCatching {
                if (kind == DetailKind.GENRE_TRACKS) {
                    repository.genreTracks(itemId, _genreTracksPerMix.value)
                } else {
                    repository.childItems(itemId, kind)
                }
            }.fold(
                onSuccess = {
                    _state.value = TabState.Data(it)
                    loadSimilarCollections(it)
                },
                onFailure = {
                    _state.value = TabState.Error(it.message ?: "Failed to load")
                    _similarState.value = SimilarCollectionsState.Hidden
                },
            )
        }
    }

    private fun hydrateArtistAlbumArt(items: List<LibraryItem>) {
        val ids = items.map { it.id }
        viewModelScope.launch {
            runCatching { repository.hydrateArtistAlbumArt(itemId, items) }
                .onSuccess { hydrated ->
                    val current = _state.value as? TabState.Data ?: return@onSuccess
                    if (current.items.map { it.id } == ids) {
                        _state.value = TabState.Data(hydrated)
                    }
                }
        }
    }

    private fun loadSimilarCollections(items: List<LibraryItem>) {
        when (kind) {
            DetailKind.ARTIST_ALBUMS -> {
                hydrateArtistAlbumArt(items)
                loadSimilarArtists()
            }
            DetailKind.ALBUM_TRACKS -> loadSimilarAlbums(items)
            DetailKind.GENRE_TRACKS,
            DetailKind.AUTHOR_BOOKS,
            DetailKind.BOOK_CHAPTERS,
            DetailKind.PLAYLIST_TRACKS -> _similarState.value = SimilarCollectionsState.Hidden
        }
    }

    private fun loadSimilarArtists() {
        _similarState.value = SimilarCollectionsState.Loading("Sonically similar artists")
        viewModelScope.launch {
            runCatching {
                val seed = repository.playableItems(itemId, DetailKind.ARTIST_ALBUMS).firstOrNull()
                    ?: return@runCatching emptyList()
                coordinator.similarArtists(seed.id, SIMILAR_COLLECTION_LIMIT)
                    .mapNotNull { result ->
                        repository.searchArtists(result.artist, limit = SIMILAR_SEARCH_LIMIT)
                            .firstOrNull { it.title.normalizedTitle() == result.artist.normalizedTitle() }
                            ?.copy(subtitle = scoreSubtitle(result.score))
                    }
            }.fold(
                onSuccess = { items ->
                    _similarState.value = if (items.isEmpty()) {
                        SimilarCollectionsState.Hidden
                    } else {
                        SimilarCollectionsState.Data(
                            title = "Sonically similar artists",
                            items = items.distinctBy { it.id },
                            targetKind = DetailKind.ARTIST_ALBUMS,
                        )
                    }
                },
                onFailure = { _similarState.value = SimilarCollectionsState.Error("Couldn't load similar artists") },
            )
        }
    }

    private fun loadSimilarAlbums(items: List<LibraryItem>) {
        val seed = items.firstOrNull()
        if (seed == null) {
            _similarState.value = SimilarCollectionsState.Hidden
            return
        }
        _similarState.value = SimilarCollectionsState.Loading("Sonically similar albums")
        viewModelScope.launch {
            runCatching {
                coordinator.similarAlbums(seed.id, SIMILAR_COLLECTION_LIMIT)
                    .mapNotNull { result -> resolveSimilarAlbum(result) }
            }.fold(
                onSuccess = { items ->
                    _similarState.value = if (items.isEmpty()) {
                        SimilarCollectionsState.Hidden
                    } else {
                        SimilarCollectionsState.Data(
                            title = "Sonically similar albums",
                            items = items.distinctBy { it.id },
                            targetKind = DetailKind.ALBUM_TRACKS,
                        )
                    }
                },
                onFailure = { _similarState.value = SimilarCollectionsState.Error("Couldn't load similar albums") },
            )
        }
    }

    private suspend fun resolveSimilarAlbum(result: SimilarAlbumDto): LibraryItem? =
        repository.searchAlbums(result.album, limit = SIMILAR_SEARCH_LIMIT)
            .firstOrNull { candidate ->
                candidate.title.normalizedTitle() == result.album.normalizedTitle() &&
                    result.artist?.let { artist ->
                        candidate.subtitle?.normalizedTitle() == artist.normalizedTitle()
                    } != false
            }
            ?.let { album ->
                val art = album.imageUrl
                    ?: repository.childItems(album.id, DetailKind.ALBUM_TRACKS)
                        .firstOrNull { it.imageUrl != null }
                        ?.imageUrl
                album.copy(subtitle = scoreSubtitle(result.score), imageUrl = art)
            }

    // Transient one-shot messages (playlist created / failed) for a snackbar.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    /** Sonic "more like this": seed track + its nearest neighbours → Emby playlist. */
    fun createSimilarPlaylist(seed: LibraryItem) = generate(
        name = "Similar to ${seed.title}",
        build = { playlists.similarTrackIds(seed.id) },
    )

    /** Sonic radio: a longer seeded sequence → Emby playlist. */
    fun createRadioPlaylist(seed: LibraryItem) = generate(
        name = "${seed.title} Radio",
        build = { playlists.radioTrackIds(seed.id) },
    )

    /** Recent-plays source for this drill-down (the album/playlist being viewed). */
    private fun currentSource(cover: String?): PlaybackSource? =
        playbackSourceFor(kind, LibraryItem(id = itemId, title = title, subtitle = null, imageUrl = cover), cover)

    fun playFrom(seed: LibraryItem) {
        val items = (state.value as? TabState.Data)?.items.orEmpty().ifEmpty { listOf(seed) }
        playback.playQueue(items, seed, currentSource(seed.imageUrl))
        viewModelScope.launch { _openNowPlaying.send(Unit) }
    }

    fun playFirst() {
        val items = (state.value as? TabState.Data)?.items.orEmpty()
        val seed = if (kind == DetailKind.BOOK_CHAPTERS) items.resumeStartItem() else items.firstOrNull()
        if (seed == null) {
            viewModelScope.launch { _messages.send("Nothing playable here") }
            return
        }
        playback.playQueue(items, seed, currentSource(seed.imageUrl))
        viewModelScope.launch { _openNowPlaying.send(Unit) }
    }

    fun shuffleAll() {
        val items = (state.value as? TabState.Data)?.items.orEmpty()
        if (items.isEmpty()) return
        val shuffled = items.shuffledMovingFirst()
        _state.update { current ->
            if (current is TabState.Data) current.copy(items = shuffled) else current
        }
        playback.prepareQueue(shuffled, shuffled = true, source = currentSource(shuffled.firstOrNull()?.imageUrl))
    }

    fun saveCurrentAsPlaylist(name: String) {
        val items = (state.value as? TabState.Data)?.items.orEmpty()
        if (items.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                playlists.createPlaylist(
                    name = name.ifBlank { defaultPlaylistName() },
                    trackIds = items.map { it.id },
                )
            }.fold(
                onSuccess = { _messages.send("Saved $it tracks to Playlists") },
                onFailure = { _messages.send("Couldn't save playlist: ${it.message}") },
            )
        }
    }

    fun removePlaylistItem(item: LibraryItem) {
        if (kind != DetailKind.PLAYLIST_TRACKS) return
        val entryId = item.playlistItemId
        if (entryId.isNullOrBlank()) {
            viewModelScope.launch { _messages.send("Couldn't remove \"${item.title}\": missing playlist entry id") }
            return
        }
        viewModelScope.launch {
            runCatching { playlists.removePlaylistItem(itemId, entryId) }.fold(
                onSuccess = {
                    _state.update { current ->
                        if (current is TabState.Data) {
                            current.copy(items = current.items.filterNot { it.playlistItemId == entryId })
                        } else {
                            current
                        }
                    }
                    _messages.send("Removed \"${item.title}\" from playlist")
                },
                onFailure = { _messages.send("Couldn't remove track: ${it.message}") },
            )
        }
    }

    fun playCollection(item: LibraryItem) {
        viewModelScope.launch {
            val targetKind = kind.childKind ?: kind
            runCatching { repository.playableItems(item.id, targetKind) }.fold(
                onSuccess = { items ->
                    val first = if (targetKind == DetailKind.BOOK_CHAPTERS) items.resumeStartItem() else items.firstOrNull()
                    if (first == null) {
                        _messages.send("Nothing playable in \"${item.title}\"")
                    } else {
                        playback.playQueue(items, first, playbackSourceFor(targetKind, item))
                        _openNowPlaying.send(Unit)
                    }
                },
                onFailure = { _messages.send("Couldn't start playback: ${it.message}") },
            )
        }
    }

    private fun generate(name: String, build: suspend () -> List<String>) {
        viewModelScope.launch {
            runCatching { playlists.createPlaylist(name, build()) }.fold(
                onSuccess = { _messages.send("Saved \"$name\" to Emby ($it tracks)") },
                onFailure = { _messages.send("Couldn't create playlist: ${it.message}") },
            )
        }
    }
}

sealed interface SimilarCollectionsState {
    data object Hidden : SimilarCollectionsState
    data class Loading(val title: String) : SimilarCollectionsState
    data class Data(
        val title: String,
        val items: List<LibraryItem>,
        val targetKind: DetailKind,
    ) : SimilarCollectionsState
    data class Error(val message: String) : SimilarCollectionsState
}

private fun DetailViewModel.defaultPlaylistName(): String =
    if (kind == DetailKind.GENRE_TRACKS) "$title genre mix" else title.ifBlank { "New playlist" }

internal val GenreMixTrackCounts = listOf(25, 50, 75, 100)
private const val DEFAULT_GENRE_MIX_TRACKS = 25
private const val SIMILAR_COLLECTION_LIMIT = 8
private const val SIMILAR_SEARCH_LIMIT = 8

private fun scoreSubtitle(score: Double): String =
    "${(score * 100).toInt().coerceIn(0, 100)}% sonic match"

private fun String.normalizedTitle(): String =
    trim().lowercase().removePrefix("the ")

private fun List<LibraryItem>.shuffledMovingFirst(): List<LibraryItem> {
    if (size < 2) return this
    val shuffled = shuffled(Random(System.nanoTime()))
    return if (shuffled.first().id != first().id) {
        shuffled
    } else {
        shuffled.drop(1) + shuffled.first()
    }
}
