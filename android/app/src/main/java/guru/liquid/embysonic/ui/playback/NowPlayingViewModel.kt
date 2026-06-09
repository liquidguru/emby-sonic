package guru.liquid.embysonic.ui.playback

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackUiState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playback: PlaybackController,
) : ViewModel() {
    val state: StateFlow<PlaybackUiState> = playback.state

    fun togglePlayPause() = playback.togglePlayPause()
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)
    fun skipPrevious() = playback.skipPrevious()
    fun skipNext() = playback.skipNext()
    fun seekToQueueIndex(index: Int) = playback.seekToQueueIndex(index)
    fun toggleShuffle() = playback.toggleShuffle()
    fun cycleRepeatMode() = playback.cycleRepeatMode()
}
