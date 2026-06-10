package guru.liquid.embysonic.ui.mixes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.BuildMixesRequestDto
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDto
import guru.liquid.embysonic.data.coordinator.dto.TrackOutDto
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.playlist.PlaylistRepository
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.ui.library.TabState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    init {
        load()
        loadSonicMixes()
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
                    items.firstOrNull()?.let { playback.playQueue(items, it) }
                },
                onFailure = {},
            )
        }
    }

    fun loadSonicMixes() {
        _sonicState.value = SonicMixesState.Loading
        viewModelScope.launch {
            runCatching { coordinator.mixes() }.fold(
                onSuccess = { mixes -> _sonicState.value = SonicMixesState.ListData(mixes) },
                onFailure = { _sonicState.value = SonicMixesState.Error(it.message ?: "Failed to load mixes") },
            )
        }
    }

    fun openSonicMix(mix: SonicMixDto) {
        _sonicState.value = SonicMixesState.DetailLoading(mix)
        viewModelScope.launch {
            runCatching { coordinator.mixDetail(mix.id) }.fold(
                onSuccess = { detail ->
                    _sonicState.value = SonicMixesState.DetailData(
                        mix = detail.mix,
                        tracks = detail.tracks.map { it.toLibraryItem() },
                    )
                },
                onFailure = {
                    _sonicState.value = SonicMixesState.Error(it.message ?: "Failed to load mix")
                },
            )
        }
    }

    fun closeSonicMix() {
        loadSonicMixes()
    }

    fun playSonicMix(mix: SonicMixDto) {
        viewModelScope.launch {
            runCatching { coordinator.mixDetail(mix.id).tracks.map { it.toLibraryItem() } }.onSuccess { tracks ->
                tracks.firstOrNull()?.let { playback.playQueue(tracks, it) }
            }
        }
    }

    fun playSonicTracks(tracks: List<LibraryItem>, start: LibraryItem) {
        playback.playQueue(tracks, start)
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

    fun setTracksPerMix(value: Int) {
        _mixOptions.value = _mixOptions.value.copy(tracksPerMix = value)
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
                    delay(MIX_GENERATION_REFRESH_DELAY_MS)
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

    private fun TrackOutDto.toLibraryItem(): LibraryItem = LibraryItem(
        id = id,
        title = title.orEmpty().ifBlank { "Unknown track" },
        subtitle = artist,
        imageUrl = null,
        trailingText = formatDuration(durationMs),
        album = album,
        durationMs = durationMs,
    )

    private fun formatDuration(ms: Long?): String? {
        if (ms == null || ms <= 0) return null
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, remainingMinutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private companion object {
        const val DEFAULT_MIX_COUNT = 30
        const val MIX_GENERATION_REFRESH_DELAY_MS = 25_000L
    }
}

data class SonicMixOptions(
    val tracksPerMix: Int = 50,
    val generating: Boolean = false,
    val message: String? = null,
)

sealed interface SonicMixesState {
    data object Loading : SonicMixesState
    data class Error(val message: String) : SonicMixesState
    data class ListData(val mixes: List<SonicMixDto>) : SonicMixesState
    data class DetailLoading(val mix: SonicMixDto) : SonicMixesState
    data class DetailData(val mix: SonicMixDto, val tracks: List<LibraryItem>) : SonicMixesState
}

fun SonicMixDto.displayTitle(): String {
    val base = name?.takeIf { it.isNotBlank() } ?: "Sonic mix"
    return base.replace(""" \(\d+\)$""".toRegex(), "")
}
