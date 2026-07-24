package guru.liquid.embysonic.ui.artistmix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.bufferedCoordinatorTrackCount
import guru.liquid.embysonic.data.coordinator.dto.ArtistMixRequestDto
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.preferredLibrary
import guru.liquid.embysonic.data.playlist.PlaylistRepository
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistMixUiState(
    val query: String = "",
    // Artists chosen so far — the "mix" being built (chips).
    val selected: List<LibraryItem> = emptyList(),
    // Artists currently offered in the grid (similars of the last pick, search
    // results, or the recently-played starting set).
    val grid: List<LibraryItem> = emptyList(),
    /**
     * Why the grid isn't showing similar artists, when it isn't. The A-Z fallback is
     * useful — you can still build a mix mid-scan — but unexplained it looks like a
     * random junk grid, so it has to say which of these happened. Null = the grid is
     * genuinely what it claims to be.
     */
    val gridNotice: String? = null,
    val loadingGrid: Boolean = false,
    val building: Boolean = false,
) {
    val canBuild: Boolean get() = selected.isNotEmpty() && !building
}

/**
 * Artist Mix Creator: pick an artist, the grid repopulates with sonically similar
 * artists (seeded from that pick), repeat to grow a selection, then build a mix
 * sequenced across the chosen artists and play it. "Build then play" flow.
 *
 * Performance: the coordinator returns similar artists by NAME. Resolving each
 * name with an Emby search call was far too slow (~a minute per grid). Instead we
 * load the full album-artist list ONCE on open and resolve names against an
 * in-memory index — grid refreshes and the search box are then instant.
 */
@HiltViewModel
class ArtistMixViewModel @Inject constructor(
    private val coordinator: CoordinatorApi,
    private val repository: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val playback: PlaybackController,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistMixUiState())
    val state: StateFlow<ArtistMixUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    private var musicLibraryId: String? = null
    private var gridJob: Job? = null

    // The whole album-artist list, loaded once, plus a normalized-name index for
    // instant resolution of coordinator results.
    private var allArtists: List<LibraryItem> = emptyList()
    private var artistIndex: Map<String, LibraryItem> = emptyMap()

    init {
        _state.update { it.copy(loadingGrid = true) }
        viewModelScope.launch {
            musicLibraryId = runCatching { repository.audioLibraries() }
                .getOrDefault(emptyList())
                .preferredLibrary(
                    LibraryKind.MUSIC,
                    settings.selectedMusicLibraryId.first(),
                )?.id
            val libId = musicLibraryId
            if (libId != null) {
                allArtists = runCatching { repository.artists(libId) }
                    .getOrDefault(emptyList())
                // First occurrence of a normalized name wins (collapses dup tags).
                artistIndex = allArtists.associateByNormalizedName()
            }
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
        // Instant local filter against the loaded artist list. These are real search
        // results, so any fallback notice no longer applies.
        val matches = allArtists
            .filter { it.title.contains(query, ignoreCase = true) }
            .excludeSelected()
            .take(GRID_LIMIT)
        _state.update { it.copy(grid = matches, gridNotice = null, loadingGrid = false) }
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
        _state.update { it.copy(loadingGrid = true) }
        gridJob?.cancel()
        gridJob = viewModelScope.launch {
            // Keep the failure rather than swallowing it: a dead coordinator and an
            // unanalysed artist both yield "no similars", but they are not the same
            // thing and the user can only act on one of them.
            val outcome = runCatching {
                val seedTrackId = repository.playableItems(item.id, DetailKind.ARTIST_ALBUMS)
                    .firstOrNull()?.id ?: return@runCatching emptyList<String>()
                coordinator.similarArtists(seedTrackId, GRID_LIMIT).map { it.artist }
            }
            val names = outcome.getOrNull()
            val similar = names?.let { resolveNames(it) }.orEmpty()
            if (similar.isNotEmpty()) {
                _state.update {
                    it.copy(grid = similar.excludeSelected(), gridNotice = null, loadingGrid = false)
                }
                return@launch
            }
            // No similars. Still fall back to A–Z — a mix can be built regardless,
            // and mid-first-scan that's the normal state — but never silently: an
            // unexplained alphabet reads as a broken random grid.
            val notice = when {
                outcome.isFailure ->
                    "Can't reach the sonic analysis backend — showing all artists."
                names.isNullOrEmpty() ->
                    "${item.title} isn't analysed yet — showing all artists."
                else ->
                    "No similar artists found for ${item.title} — showing all artists."
            }
            _state.update {
                it.copy(
                    grid = allArtists.take(GRID_LIMIT).excludeSelected(),
                    gridNotice = notice,
                    loadingGrid = false,
                )
            }
        }
    }

    private fun loadStartingGrid() {
        val libId = musicLibraryId
        _state.update { it.copy(loadingGrid = true) }
        gridJob?.cancel()
        gridJob = viewModelScope.launch {
            val recentNames = libId?.let {
                runCatching { repository.recentlyPlayedArtistNames(it, GRID_LIMIT) }.getOrDefault(emptyList())
            }.orEmpty()
            val recent = resolveNames(recentNames)
            // Fall back to a plain A–Z list if nothing's been played yet. No notice:
            // nothing has been picked, so the grid isn't claiming to be similar to
            // anything — it's just a starting point.
            val grid = recent.ifEmpty { allArtists.take(GRID_LIMIT) }
            _state.update { it.copy(grid = grid.excludeSelected(), gridNotice = null, loadingGrid = false) }
        }
    }

    fun build() {
        val artists = _state.value.selected
        if (artists.isEmpty()) {
            viewModelScope.launch { _messages.send("Pick at least one artist first") }
            return
        }
        _state.update { it.copy(building = true) }
        viewModelScope.launch {
            // Total comes from the shared "tracks per generated mix" setting; split
            // a buffered request evenly across the chosen artists, then let Emby
            // remove inaccessible tracks before trimming back to the requested total.
            val total = settings.generatedMixTracks.first()
            val requestTotal = bufferedCoordinatorTrackCount(total)
            val perArtist = ((requestTotal + artists.size - 1) / artists.size).coerceAtLeast(1)
            runCatching {
                val coordinatorTracks = coordinator.artistMix(
                    ArtistMixRequestDto(
                        artists = artists.map { it.title },
                        perArtist = perArtist,
                        length = requestTotal,
                    ),
                ).tracks
                repository.itemsByIds(coordinatorTracks.map { it.id }).take(total)
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
                            title = "Artist Mix Creator",
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

    /** Resolve coordinator artist names against the in-memory index (no network). */
    private fun resolveNames(names: List<String>): List<LibraryItem> =
        names.mapNotNull { artistIndex[it.normalized()] }.distinctBy { it.id }

    private fun List<LibraryItem>.associateByNormalizedName(): Map<String, LibraryItem> {
        val map = LinkedHashMap<String, LibraryItem>()
        for (item in this) map.putIfAbsent(item.title.normalized(), item)
        return map
    }

    private fun List<LibraryItem>.excludeSelected(): List<LibraryItem> {
        val selectedIds = _state.value.selected.map { it.id }.toSet()
        return filterNot { it.id in selectedIds }.distinctBy { it.id }
    }

    private fun String.normalized(): String = trim().lowercase()

    private companion object {
        const val GRID_LIMIT = 24
    }
}
