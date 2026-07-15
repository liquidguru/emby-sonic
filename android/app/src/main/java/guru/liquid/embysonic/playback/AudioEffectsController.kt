package guru.liquid.embysonic.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import guru.liquid.embysonic.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One equalizer band: its center frequency and current gain. */
data class EqBand(val index: Int, val centerFreqHz: Int, val levelMb: Int)

data class EqualizerState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val bands: List<EqBand> = emptyList(),
    val presets: List<String> = emptyList(),
    /**
     * Index of the active built-in preset, or [NO_PRESET] when the bands have
     * been set manually (the system reports `PRESET_UNDEFINED` once any band is
     * adjusted away from a preset's curve).
     */
    val currentPreset: Int = NO_PRESET,
) {
    companion object {
        const val NO_PRESET = -1
    }
}

/**
 * Owns the system [Equalizer] bound to the shared audio session of both
 * playback ExoPlayers, so EQ applies to normal playback and crossfade blends
 * alike. Settings persist via [SettingsRepository]. Also broadcasts the
 * audio-session open intent so a system / third-party equalizer (e.g. Wavelet)
 * can attach to liquidWave's output.
 *
 * Robust to devices without an effects implementation (e.g. some emulators):
 * [attach] simply leaves the equalizer unavailable and the UI reflects that.
 */
@Singleton
class AudioEffectsController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var equalizer: Equalizer? = null
    private var sessionId: Int = 0
    private var suppressedForRemotePlayback: Boolean = false

    /**
     * A second Equalizer for the crossfade helper's OWN audio session.
     *
     * The two players used to share one session so a single Equalizer covered
     * both. That turned out to dip the audio at every blend: when the primary
     * jumps to the next track it rebuilds its AudioTrack, Android reconfigures
     * the session's effect chain, and that interrupts everything in the session
     * — including the helper carrying the outgoing song. Confirmed by the dip
     * disappearing with the EQ switched off.
     *
     * Separate sessions isolate them; mirroring the settings here keeps EQ
     * applied to both sides of a blend, which is why they were shared to begin
     * with. Never drives [_state] — the UI reflects the primary only.
     */
    private var helperEqualizer: Equalizer? = null

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    /** Bind the equalizer to the players' shared audio session and broadcast it. */
    fun attach(audioSessionId: Int) {
        sessionId = audioSessionId
        val eq = runCatching { Equalizer(EFFECT_PRIORITY, audioSessionId) }
            .onFailure { Log.w(TAG, "Equalizer unavailable on this device", it) }
            .getOrNull()
        if (eq == null) {
            _state.value = EqualizerState(available = false)
            return
        }
        equalizer = eq

        val saved = settings.snapshot()
        runCatching {
            eq.enabled = saved.eqEnabled
            if (saved.eqPreset in 0 until eq.numberOfPresets) {
                // A built-in preset was selected; re-apply it so currentPreset
                // reports it again and the chip shows as selected after restart.
                eq.usePreset(saved.eqPreset.toShort())
            } else {
                saved.eqBandLevels.forEachIndexed { band, level ->
                    if (band < eq.numberOfBands) eq.setBandLevel(band.toShort(), level.toShort())
                }
            }
        }
        publish()
        broadcastOpen()
    }

    /**
     * Bind a mirrored equalizer to the crossfade helper's own session, so the
     * outgoing track keeps its EQ through a blend. Called when the helper is
     * created; [detachHelper] when it is released.
     */
    fun attachHelper(audioSessionId: Int) {
        if (helperEqualizer != null) return
        val primary = equalizer ?: return   // no EQ on this device — nothing to mirror
        helperEqualizer = runCatching { Equalizer(EFFECT_PRIORITY, audioSessionId) }
            .onFailure { Log.w(TAG, "Helper equalizer unavailable", it) }
            .getOrNull()
        mirrorToHelper(primary)
        // Deliberately NOT broadcasting the effect-control-session intent for
        // this one: a third-party EQ should attach to our real output, not to a
        // transient blend helper.
    }

    fun detachHelper() {
        helperEqualizer?.let { runCatching { it.release() } }
        helperEqualizer = null
    }

    /** Copy the primary's live curve + enabled state onto the helper's EQ. */
    private fun mirrorToHelper(primary: Equalizer) {
        val helper = helperEqualizer ?: return
        runCatching {
            helper.enabled = primary.enabled
            for (b in 0 until minOf(primary.numberOfBands.toInt(), helper.numberOfBands.toInt())) {
                helper.setBandLevel(b.toShort(), primary.getBandLevel(b.toShort()))
            }
        }
    }

    /** Re-apply the primary's current settings to the helper, if one is attached. */
    private fun syncHelper() {
        val primary = equalizer ?: return
        if (helperEqualizer != null) mirrorToHelper(primary)
    }

    fun setEnabled(enabled: Boolean) {
        equalizer?.let { runCatching { it.enabled = enabled && !suppressedForRemotePlayback } }
        syncHelper()
        scope.launch { settings.setEqEnabled(enabled) }
        publish()
    }

    fun setSuppressedForRemotePlayback(suppressed: Boolean) {
        if (suppressedForRemotePlayback == suppressed) return
        suppressedForRemotePlayback = suppressed
        val savedEnabled = settings.snapshot().eqEnabled
        equalizer?.let { runCatching { it.enabled = savedEnabled && !suppressed } }
        syncHelper()
        publish()
    }

    fun setBandLevel(index: Int, levelMb: Int) {
        val eq = equalizer ?: return
        runCatching {
            val (min, max) = eq.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            eq.setBandLevel(index.toShort(), levelMb.coerceIn(min, max).toShort())
        }
        syncHelper()
        publish()
    }

    /**
     * Persist the current live band values after a slider drag finishes. A manual
     * adjustment means we're no longer on a preset, so clear the saved preset too.
     */
    fun persistBandLevels() {
        if (equalizer == null) return
        scope.launch {
            settings.setEqPreset(EqualizerState.NO_PRESET)
            settings.setEqBandLevels(currentLevels())
        }
    }

    /** Apply a built-in preset, then persist the preset and its per-band gains. */
    fun usePreset(preset: Int) {
        val eq = equalizer ?: return
        runCatching { eq.usePreset(preset.toShort()) }
        syncHelper()
        scope.launch {
            settings.setEqPreset(preset)
            settings.setEqBandLevels(currentLevels())
        }
        publish()
    }

    /** Reset every band to 0 dB (flat) and drop any selected preset. */
    fun reset() {
        val eq = equalizer ?: return
        runCatching {
            for (b in 0 until eq.numberOfBands) eq.setBandLevel(b.toShort(), 0)
        }
        syncHelper()
        scope.launch {
            settings.setEqPreset(EqualizerState.NO_PRESET)
            settings.setEqBandLevels(currentLevels())
        }
        publish()
    }

    private fun currentLevels(): List<Int> {
        val eq = equalizer ?: return emptyList()
        return (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
    }

    private fun publish() {
        val eq = equalizer
        if (eq == null || suppressedForRemotePlayback) {
            _state.value = EqualizerState(available = false)
            return
        }
        val state = runCatching {
            val range = eq.bandLevelRange
            val bands = (0 until eq.numberOfBands).map { b ->
                EqBand(
                    index = b,
                    centerFreqHz = eq.getCenterFreq(b.toShort()) / 1000, // millihertz → Hz
                    levelMb = eq.getBandLevel(b.toShort()).toInt(),
                )
            }
            val presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
            EqualizerState(
                available = true,
                enabled = eq.enabled,
                minLevelMb = range[0].toInt(),
                maxLevelMb = range[1].toInt(),
                bands = bands,
                presets = presets,
                // currentPreset is PRESET_UNDEFINED (-1) once any band is moved.
                currentPreset = eq.currentPreset.toInt(),
            )
        }.getOrNull() ?: EqualizerState(available = false)
        _state.value = state
    }

    private fun broadcastOpen() {
        runCatching {
            context.sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                },
            )
        }
    }

    private companion object {
        const val EFFECT_PRIORITY = 0
        const val TAG = "AudioEffects"
    }
}
