package guru.liquid.embysonic.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.toLibraryItem
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackSource
import guru.liquid.embysonic.playback.PlaybackUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Sonic Track Radio for the Now Playing "Radio" tab, seeded from the current track. */
sealed interface RadioState {
    data object Idle : RadioState
    data object Loading : RadioState
    data class Data(val tracks: List<LibraryItem>) : RadioState
    data class Error(val message: String) : RadioState
}

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playback: PlaybackController,
    private val coordinator: CoordinatorApi,
    private val repository: LibraryRepository,
) : ViewModel() {
    val state: StateFlow<PlaybackUiState> = playback.state

    private val _radio = MutableStateFlow<RadioState>(RadioState.Idle)
    val radio: StateFlow<RadioState> = _radio.asStateFlow()

    // The track the current radio was generated from, so we don't reload on every
    // recomposition but can refresh when the seed changes (or on explicit request).
    private var radioSeedId: String? = null

    // The in-flight radio build. The seed can change while a request is pending
    // (track auto-advances, or the user hits "New radio"); cancel the previous so
    // a slow earlier response can't land after — and overwrite — a newer one.
    private var radioJob: Job? = null

    fun togglePlayPause() = playback.togglePlayPause()
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)
    fun skipPrevious() = playback.skipPrevious()
    fun skipNext() = playback.skipNext()
    fun seekToQueueIndex(index: Int) = playback.seekToQueueIndex(index)
    fun shuffleQueue() = playback.shuffleQueue()
    fun cycleRepeatMode() = playback.cycleRepeatMode()
    fun stopPlayback() = playback.stopPlayback()
    fun setSleepTimer(durationMs: Long) = playback.setSleepTimer(durationMs)
    fun setSleepTimerEndOfTrack() = playback.setSleepTimerEndOfTrack()
    fun cancelSleepTimer() = playback.cancelSleepTimer()
    fun setAudiobookSpeed(speed: Float) = playback.setAudiobookSpeed(speed)
    fun setGuestDjEnabled(enabled: Boolean) = playback.setGuestDjEnabled(enabled)
    fun setCastVolume(volume: Float) = playback.setCastVolume(volume)

    /** Generate a sonic radio for the current track (no-op if already loaded for it). */
    fun loadRadioForCurrent(force: Boolean = false) {
        val seed = playback.state.value.currentTrack ?: return
        if (!force && seed.id == radioSeedId && _radio.value is RadioState.Data) return
        radioSeedId = seed.id
        _radio.value = RadioState.Loading
        radioJob?.cancel()
        radioJob = viewModelScope.launch {
            runCatching {
                val tracks = coordinator.trackRadio(seed.id).tracks.map { it.toLibraryItem() }
                val art = runCatching { repository.artworkByIds(tracks.map { it.id }) }
                    .getOrDefault(emptyMap())
                tracks.map { t -> art[t.id]?.let { t.copy(imageUrl = it) } ?: t }
            }.fold(
                onSuccess = { _radio.value = RadioState.Data(it) },
                onFailure = { _radio.value = RadioState.Error(it.message ?: "Couldn't build radio") },
            )
        }
    }

    /** Play the whole radio queue from the top. */
    fun playRadioAll() {
        val tracks = (_radio.value as? RadioState.Data)?.tracks ?: return
        val first = tracks.firstOrNull() ?: return
        playback.playQueue(tracks, first, radioSource(tracks))
    }

    /** Play the radio queue starting from [item]. */
    fun playRadioTrack(item: LibraryItem) {
        val tracks = (_radio.value as? RadioState.Data)?.tracks ?: return
        playback.playQueue(tracks, item, radioSource(tracks))
    }

    /** Recent-plays source for a Track Radio, keyed by its seed track. */
    private fun radioSource(tracks: List<LibraryItem>): PlaybackSource {
        val seedTitle = playback.state.value.currentTrack?.title ?: "current track"
        return PlaybackSource("radio:$radioSeedId", "Track Radio", "Based on $seedTitle", tracks.firstOrNull()?.imageUrl)
    }
}
