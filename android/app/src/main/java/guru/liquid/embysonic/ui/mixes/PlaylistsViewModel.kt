package guru.liquid.embysonic.ui.mixes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.toLibraryItem
import guru.liquid.embysonic.data.coordinator.dto.BuildMixesRequestDto
import guru.liquid.embysonic.data.coordinator.dto.RegenerateMixRequestDto
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDto
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.playlist.PlaylistRepository
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackSource
import guru.liquid.embysonic.ui.library.TabState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Backs the Playlists tab of the Mixes screen: lists the user's Emby playlists. */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val coordinator: CoordinatorApi,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    /** Card grid vs. list, persisted and shared with the library/detail screens. */
    val listView: StateFlow<Boolean> =
        settings.libraryListView.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleListView() = viewModelScope.launch { settings.setLibraryListView(!listView.value) }

    private val _state = MutableStateFlow<TabState>(TabState.Loading)
    val state: StateFlow<TabState> = _state.asStateFlow()

    private val _sonicState = MutableStateFlow<SonicMixesState>(SonicMixesState.Loading)
    val sonicState: StateFlow<SonicMixesState> = _sonicState.asStateFlow()

    private val _mixOptions = MutableStateFlow(SonicMixOptions())
    val mixOptions: StateFlow<SonicMixOptions> = _mixOptions.asStateFlow()

    // Open Now Playing only after a playable queue actually loads; surface
    // failures as a snackbar instead of dropping the user on an empty player.
    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    // The last loaded mix list (with covers), so backing out of a mix detail
    // restores it instantly instead of refetching (and losing the user's place).
    private var lastList: SonicMixesState.ListData? = null

    init {
        observeGeneratedMixTracks()
        observeCoordinatorUrl()
        load()
        loadSonicMixes()
    }

    /**
     * Reload when the coordinator URL changes. Fixing a wrong URL in Settings used to
     * leave this tab sitting on its old error until you found the refresh button —
     * which is exactly the moment a new user has just corrected it and expects it to
     * work. `drop(1)` skips the current value replayed on collect, so this only fires
     * on an actual change and never double-loads over the init call above.
     */
    private fun observeCoordinatorUrl() {
        viewModelScope.launch {
            settings.settings
                .map { it.coordinatorUrl }
                .distinctUntilChanged()
                .drop(1)
                .collect { loadSonicMixes() }
        }
    }

    private fun observeGeneratedMixTracks() {
        viewModelScope.launch {
            settings.generatedMixTracks.distinctUntilChanged().collect { count ->
                _mixOptions.value = _mixOptions.value.copy(
                    tracksPerMix = count,
                    refreshTracksPerMix = count,
                )
            }
        }
    }

    fun load() {
        _state.value = TabState.Loading
        viewModelScope.launch {
            runCatching { repository.playlists() }.fold(
                onSuccess = { _state.value = TabState.Data(it) },
                onFailure = { _state.value = TabState.Error(it.message ?: "Failed to load") },
            )
        }
    }

    fun playPlaylist(item: LibraryItem) {
        viewModelScope.launch {
            runCatching { repository.playableItems(item.id, guru.liquid.embysonic.data.emby.DetailKind.PLAYLIST_TRACKS) }.fold(
                onSuccess = { items ->
                    val first = items.firstOrNull()
                    if (first == null) {
                        _messages.send("Nothing playable in \"${item.title}\"")
                    } else {
                        playback.playQueue(
                            items,
                            first,
                            PlaybackSource("playlist:${item.id}", item.title, "Playlist", item.imageUrl),
                        )
                        _openNowPlaying.send(Unit)
                    }
                },
                onFailure = { _messages.send("Couldn't start playback: ${it.message}") },
            )
        }
    }

    fun loadSonicMixes() {
        _sonicState.value = SonicMixesState.Loading
        viewModelScope.launch {
            runCatching { coordinator.mixes() }.fold(
                onSuccess = { mixes ->
                    // Resolve a cover image per mix from its representative track id.
                    val art = runCatching {
                        repository.artworkByIds(mixes.mapNotNull { it.coverTrackId })
                    }.getOrDefault(emptyMap())
                    val covers = mixes.associate { it.id to it.coverTrackId?.let { tid -> art[tid] } }
                    val list = SonicMixesState.ListData(mixes, covers)
                    lastList = list
                    _sonicState.value = list
                },
                onFailure = { _sonicState.value = SonicMixesState.Error(it.mixesErrorMessage()) },
            )
        }
    }

    /**
     * A dead coordinator surfaces here as a raw OkHttp exception ("Failed to connect
     * to <host>/<ip>:8765"), which tells a user nothing they can act on. Sonic Mixes
     * only exist with the backend, so name that and point at the fix.
     */
    private fun Throwable.mixesErrorMessage(): String = when (this) {
        is IOException ->
            "Can't reach the sonic analysis backend, so Sonic Mixes aren't available. " +
                "Check the coordinator URL in Settings."
        else -> message ?: "Failed to load mixes"
    }

    fun openSonicMix(mix: SonicMixDto) {
        _sonicState.value = SonicMixesState.DetailLoading(mix)
        _mixOptions.value = _mixOptions.value.copy(message = null)
        viewModelScope.launch {
            runCatching { coordinator.mixDetail(mix.id) }.fold(
                onSuccess = { detail ->
                    _sonicState.value = SonicMixesState.DetailData(
                        mix = detail.mix,
                        tracks = detail.tracks.map { it.toLibraryItem() }.withArtwork(),
                    )
                },
                onFailure = {
                    _sonicState.value = SonicMixesState.Error(it.message ?: "Failed to load mix")
                },
            )
        }
    }

    fun closeSonicMix() {
        // Restore the cached list (with covers) instantly; only refetch if absent.
        lastList?.let { _sonicState.value = it } ?: loadSonicMixes()
    }

    fun playSonicMix(mix: SonicMixDto) {
        viewModelScope.launch {
            runCatching { coordinator.mixDetail(mix.id).tracks.map { it.toLibraryItem() }.withArtwork() }.fold(
                onSuccess = { tracks ->
                    val first = tracks.firstOrNull()
                    if (first == null) {
                        _messages.send("Nothing playable in \"${mix.displayTitle()}\"")
                    } else {
                        playback.playQueue(tracks, first, mix.recentSource(first.imageUrl))
                        _openNowPlaying.send(Unit)
                    }
                },
                onFailure = { _messages.send("Couldn't start playback: ${it.message}") },
            )
        }
    }

    private fun SonicMixDto.recentSource(cover: String?): PlaybackSource =
        PlaybackSource("mix:$id", displayTitle(), "Sonic mix", cover)

    fun playSonicTracks(tracks: List<LibraryItem>, start: LibraryItem) {
        if (tracks.isEmpty()) return
        // Playing from within an open mix detail: record it as that mix.
        val source = (_sonicState.value as? SonicMixesState.DetailData)?.mix?.recentSource(start.imageUrl)
        playback.playQueue(tracks, start, source)
        viewModelScope.launch { _openNowPlaying.send(Unit) }
    }

    fun saveSonicMixAsPlaylist(name: String, tracks: List<LibraryItem>) {
        _mixOptions.value = _mixOptions.value.copy(message = null)
        viewModelScope.launch {
            runCatching {
                playlists.createPlaylist(
                    name = name.ifBlank { "Sonic mix" },
                    trackIds = tracks.map { it.id },
                )
            }.fold(
                onSuccess = { count ->
                    _mixOptions.value = _mixOptions.value.copy(message = "Saved $count tracks to Playlists")
                    load()
                },
                onFailure = {
                    _mixOptions.value = _mixOptions.value.copy(message = it.message ?: "Failed to save playlist")
                },
            )
        }
    }

    fun deletePlaylist(item: LibraryItem) {
        viewModelScope.launch {
            runCatching { playlists.deletePlaylist(item.id) }.fold(
                onSuccess = { load() },
                onFailure = {
                    _mixOptions.value = _mixOptions.value.copy(
                        message = it.message ?: "Delete failed",
                    )
                },
            )
        }
    }

    fun regenerateSonicMix() {
        val mix = (_sonicState.value as? SonicMixesState.DetailData)?.mix ?: return
        val tracksPerMix = _mixOptions.value.refreshTracksPerMix
        _mixOptions.value = _mixOptions.value.copy(generating = true, message = null)
        viewModelScope.launch {
            runCatching {
                coordinator.regenerateMix(mix.id, RegenerateMixRequestDto(tracksPerMix = tracksPerMix))
            }.fold(
                onSuccess = { detail ->
                    _sonicState.value = SonicMixesState.DetailData(
                        mix = detail.mix,
                        tracks = detail.tracks.map { it.toLibraryItem() }.withArtwork(),
                    )
                    _mixOptions.value = _mixOptions.value.copy(
                        generating = false,
                        message = "Mix refreshed — ${detail.tracks.size} tracks",
                    )
                },
                onFailure = {
                    _mixOptions.value = _mixOptions.value.copy(
                        generating = false,
                        message = it.message ?: "Regeneration failed",
                    )
                },
            )
        }
    }

    fun setTracksPerMix(value: Int) {
        _mixOptions.value = _mixOptions.value.copy(tracksPerMix = value)
        viewModelScope.launch { settings.setGeneratedMixTracks(value) }
    }

    fun setRefreshTracksPerMix(value: Int) {
        _mixOptions.value = _mixOptions.value.copy(refreshTracksPerMix = value)
        viewModelScope.launch { settings.setGeneratedMixTracks(value) }
    }

    fun generateSonicMixes() {
        val options = _mixOptions.value
        if (options.generating) return
        _mixOptions.value = options.copy(generating = true, message = null)
        viewModelScope.launch {
            runCatching {
                coordinator.buildMixes(
                    BuildMixesRequestDto(
                        nClusters = DEFAULT_MIX_COUNT,
                        tracksPerMix = options.tracksPerMix,
                    ),
                )
            }.fold(
                onSuccess = {
                    _mixOptions.value = _mixOptions.value.copy(
                        message = "Generating ${it.nClusters} mixes of ${it.tracksPerMix} tracks",
                    )
                    awaitMixBuild()
                    loadSonicMixes()
                    _mixOptions.value = _mixOptions.value.copy(generating = false)
                },
                onFailure = {
                    _mixOptions.value = _mixOptions.value.copy(
                        generating = false,
                        message = it.message ?: "Failed to generate mixes",
                    )
                },
            )
        }
    }

    /**
     * Wait for a triggered mix build to finish by polling the coordinator's
     * build-state, instead of guessing a fixed delay. Waits for the build to
     * start (grace window — a tiny library can finish almost instantly), then
     * for it to finish, capped so a stuck build can't hang the UI forever.
     */
    private suspend fun awaitMixBuild() {
        var sawRunning = false
        var waited = 0L
        while (waited < MIX_BUILD_MAX_WAIT_MS) {
            delay(MIX_BUILD_POLL_MS)
            waited += MIX_BUILD_POLL_MS
            val running = runCatching { coordinator.buildState().running }.getOrDefault(false)
            if (running) {
                sawRunning = true
            } else if (sawRunning || waited >= MIX_BUILD_START_GRACE_MS) {
                break
            }
        }
    }

    /**
     * Coordinator tracks carry no artwork; resolve each track's Emby Primary
     * cover in one batched query so mix detail, Now Playing, and the mini player
     * show real art instead of placeholders. Falls back to the unhydrated items
     * if the lookup fails.
     */
    private suspend fun List<LibraryItem>.withArtwork(): List<LibraryItem> {
        val art = runCatching { repository.artworkByIds(map { it.id }) }.getOrDefault(emptyMap())
        return map { item -> art[item.id]?.let { item.copy(imageUrl = it) } ?: item }
    }

    private companion object {
        const val DEFAULT_MIX_COUNT = 30
        const val MIX_BUILD_POLL_MS = 1_500L
        const val MIX_BUILD_START_GRACE_MS = 6_000L
        const val MIX_BUILD_MAX_WAIT_MS = 180_000L
    }
}

data class SonicMixOptions(
    val tracksPerMix: Int = 25,
    val refreshTracksPerMix: Int = 25,
    val generating: Boolean = false,
    val message: String? = null,
)

sealed interface SonicMixesState {
    data object Loading : SonicMixesState
    data class Error(val message: String) : SonicMixesState
    data class ListData(
        val mixes: List<SonicMixDto>,
        val covers: Map<String, String?> = emptyMap(),
    ) : SonicMixesState
    data class DetailLoading(val mix: SonicMixDto) : SonicMixesState
    data class DetailData(val mix: SonicMixDto, val tracks: List<LibraryItem>) : SonicMixesState
}

fun SonicMixDto.displayTitle(): String {
    val base = name?.takeIf { it.isNotBlank() } ?: "Sonic mix"
    return base.replace(""" \(\d+\)$""".toRegex(), "")
}
