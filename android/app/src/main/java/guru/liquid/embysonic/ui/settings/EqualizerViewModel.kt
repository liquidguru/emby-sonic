package guru.liquid.embysonic.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.playback.AudioEffectsController
import guru.liquid.embysonic.playback.EqualizerState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val audioEffects: AudioEffectsController,
) : ViewModel() {
    val state: StateFlow<EqualizerState> = audioEffects.state

    fun setEnabled(enabled: Boolean) = audioEffects.setEnabled(enabled)
    fun setBandLevel(index: Int, levelMb: Int) = audioEffects.setBandLevel(index, levelMb)
    fun usePreset(preset: Int) = audioEffects.usePreset(preset)
    fun reset() = audioEffects.reset()
}
