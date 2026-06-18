package guru.liquid.embysonic.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
                onSuccess = { _state.value = TabState.Data(it) },
                onFailure = { _state.value = TabState.Error(it.message ?: "Failed to load") },
            )
        }
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

private fun DetailViewModel.defaultPlaylistName(): String =
    if (kind == DetailKind.GENRE_TRACKS) "$title genre mix" else title.ifBlank { "New playlist" }

internal val GenreMixTrackCounts = listOf(25, 50, 75, 100)
private const val DEFAULT_GENRE_MIX_TRACKS = 25

private fun List<LibraryItem>.shuffledMovingFirst(): List<LibraryItem> {
    if (size < 2) return this
    val shuffled = shuffled(Random(System.nanoTime()))
    return if (shuffled.first().id != first().id) {
        shuffled
    } else {
        shuffled.drop(1) + shuffled.first()
    }
}
