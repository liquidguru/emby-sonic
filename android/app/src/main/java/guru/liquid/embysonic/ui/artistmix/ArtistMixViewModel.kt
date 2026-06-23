package guru.liquid.embysonic.ui.artistmix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.ArtistMixRequestDto
import guru.liquid.embysonic.data.coordinator.toLibraryItem
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.playlist.PlaylistRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistMixUiState(
    val query: String = "",
    // Artists chosen so far — the "mix" being built (chips).
    val selected: List<LibraryItem> = emptyList(),
    // Artists currently offered in the grid (similars of the last pick, search
    // results, or the most-played starting set).
    val grid: List<LibraryItem> = emptyList(),
    val loadingGrid: Boolean = false,
    val building: Boolean = false,
) {
    val canBuild: Boolean get() = selected.isNotEmpty() && !building
}

/**
 * Artist Mix Builder: pick an artist, the grid repopulates with sonically similar
 * artists (seeded from that pick), repeat to grow a selection, then build a mix
 * sequenced across the chosen artists and play it. "Build then play" flow.
 */
@HiltViewModel
class ArtistMixViewModel @Inject constructor(
    private val coordinator: CoordinatorApi,
    private val repository: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistMixUiState())
    val state: StateFlow<ArtistMixUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    private var musicLibraryId: String? = null
    private var gridJob: Job? = null

    init {
        viewModelScope.launch {
            musicLibraryId = runCatching { repository.audioLibraries() }
                .getOrDefault(emptyList())
                .firstOrNull { it.kind == LibraryKind.MUSIC }?.id
            loadStartingGrid()
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        gridJob?.cancel()
        if (query.isBlank()) {
            reseedFromLastOrStart()
            return
        }
        _state.update { it.copy(loadingGrid = true) }
        gridJob = viewModelScope.launch {
            val results = runCatching { repository.searchArtists(query, limit = GRID_LIMIT) }
                .getOrDefault(emptyList())
            _state.update { it.copy(grid = results.excludeSelected(), loadingGrid = false) }
        }
    }

    fun selectArtist(item: LibraryItem) {
        if (_state.value.selected.any { it.id == item.id }) return
        _state.update { it.copy(selected = it.selected + item, query = "") }
        reseedFrom(item)
    }

    fun removeArtist(item: LibraryItem) {
        _state.update { it.copy(selected = it.selected.filterNot { s -> s.id == item.id }) }
        reseedFromLastOrStart()
    }

    private fun reseedFromLastOrStart() {
        val last = _state.value.selected.lastOrNull()
        if (last != null) reseedFrom(last) else loadStartingGrid()
    }

    /** Repopulate the grid with artists sonically similar to [item]. */
    private fun reseedFrom(item: LibraryItem) {
        val libId = musicLibraryId
        _state.update { it.copy(loadingGrid = true) }
        gridJob?.cancel()
        gridJob = viewModelScope.launch {
            val names = runCatching {
                val seedTrackId = repository.playableItems(item.id, DetailKind.ARTIST_ALBUMS)
                    .firstOrNull()?.id ?: return@runCatching emptyList<String>()
                coordinator.similarArtists(seedTrackId, GRID_LIMIT).map { it.artist }
            }.getOrDefault(emptyList())
            val similar = resolveArtists(names)
            // If the artist isn't analysed yet (no similars), fall back to a plain
            // A–Z list so the grid is never empty.
            val grid = similar.ifEmpty {
                libId?.let { runCatching { repository.artists(it, GRID_LIMIT) }.getOrDefault(emptyList()) }
                    ?: emptyList()
            }
            _state.update { it.copy(grid = grid.excludeSelected(), loadingGrid = false) }
        }
    }

    private fun loadStartingGrid() {
        val libId = musicLibraryId ?: return
        _state.update { it.copy(loadingGrid = true) }
        gridJob?.cancel()
        gridJob = viewModelScope.launch {
            val recentNames = runCatching { repository.recentlyPlayedArtistNames(libId, GRID_LIMIT) }
                .getOrDefault(emptyList())
            val recent = resolveArtists(recentNames)
            // Fall back to a plain A–Z list if nothing's been played yet.
            val grid = recent.ifEmpty {
                runCatching { repository.artists(libId, GRID_LIMIT) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(grid = grid.excludeSelected(), loadingGrid = false) }
        }
    }

    /**
     * Resolve coordinator artist *names* back to Emby artists (with id + image) in
     * PARALLEL. Doing these searches sequentially made each grid refresh take ~a
     * minute; fanning them out collapses it to roughly one search round-trip.
     */
    private suspend fun resolveArtists(names: List<String>): List<LibraryItem> = coroutineScope {
        names.map { name ->
            async {
                runCatching {
                    repository.searchArtists(name, limit = 5)
                        .firstOrNull { it.title.normalized() == name.normalized() }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().distinctBy { it.id }
    }

    fun build() {
        val artists = _state.value.selected
        if (artists.isEmpty()) {
            viewModelScope.launch { _messages.send("Pick at least one artist first") }
            return
        }
        _state.update { it.copy(building = true) }
        viewModelScope.launch {
            runCatching {
                val raw = coordinator.artistMix(
                    ArtistMixRequestDto(artists = artists.map { it.title }, perArtist = PER_ARTIST),
                ).tracks.map { it.toLibraryItem() }
                val art = runCatching { repository.artworkByIds(raw.map { it.id }) }
                    .getOrDefault(emptyMap())
                raw.map { t -> art[t.id]?.let { t.copy(imageUrl = it) } ?: t }
            }.fold(
                onSuccess = { tracks ->
                    _state.update { it.copy(building = false) }
                    if (tracks.isEmpty()) {
                        _messages.send("Couldn't build a mix from those artists")
                        return@fold
                    }
                    val label = artists.joinToString(", ") { it.title }.take(60)
                    playback.playQueue(
                        tracks,
                        tracks.first(),
                        PlaybackSource(
                            key = "artistmix:" + artists.joinToString("+") { it.id },
                            title = "Artist Mix",
                            subtitle = label,
                            coverUrl = tracks.first().imageUrl,
                        ),
                    )
                    _openNowPlaying.send(Unit)
                },
                onFailure = {
                    _state.update { st -> st.copy(building = false) }
                    _messages.send(it.message ?: "Couldn't build the mix")
                },
            )
        }
    }

    private fun List<LibraryItem>.excludeSelected(): List<LibraryItem> {
        val selectedIds = _state.value.selected.map { it.id }.toSet()
        return filterNot { it.id in selectedIds }.distinctBy { it.id }
    }

    private fun String.normalized(): String = trim().lowercase()

    private companion object {
        const val GRID_LIMIT = 24
        const val PER_ARTIST = 5
    }
}
