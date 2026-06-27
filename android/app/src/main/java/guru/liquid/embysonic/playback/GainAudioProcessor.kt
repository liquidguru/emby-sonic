package guru.liquid.embysonic.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Media3 [AudioProcessor] that applies a per-track linear gain to the PCM
 * stream, used for volume normalisation. The gain is set by [PlaybackController]
 * on each track transition from the track's measured loudness; it is read live
 * on the audio thread via the volatile [gain] field.
 *
 * This sits in the audio-sink processing chain and is independent of the player's
 * own `volume` (which the crossfade and fade-on-pause logic drive) — the two
 * multiply, so normalisation and fades compose correctly.
 *
 * Handles 16-bit and float PCM (the encodings the sink produces in practice).
 * For any other encoding it passes audio through unchanged, and at unity gain it
 * copies straight through. Boosts are hard-limited at the sample level so a large
 * positive gain on a quiet-but-peaky track can't wrap into distortion.
 */
@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {

    /** Linear gain factor (1.0 = unity). Read on the audio thread. */
    @Volatile
    var gain: Float = 1f

    private var encoding: Int = C.ENCODING_INVALID

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        // We can process these in place; output format matches input.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val g = gain
        val output = replaceOutputBuffer(size)
        when {
            g == 1f -> {
                // Unity: straight copy, no per-sample work.
                output.put(inputBuffer)
            }
            encoding == C.ENCODING_PCM_16BIT -> {
                inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                output.order(ByteOrder.LITTLE_ENDIAN)
                var i = inputBuffer.position()
                val end = inputBuffer.limit()
                while (i + 1 < end) {
                    val sample = inputBuffer.getShort(i).toInt()
                    val scaled = (sample * g).toInt().coerceIn(MIN_16, MAX_16)
                    output.putShort(scaled.toShort())
                    i += 2
                }
                inputBuffer.position(end)
            }
            encoding == C.ENCODING_PCM_FLOAT -> {
                inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                output.order(ByteOrder.LITTLE_ENDIAN)
                var i = inputBuffer.position()
                val end = inputBuffer.limit()
                while (i + 3 < end) {
                    val sample = inputBuffer.getFloat(i)
                    output.putFloat((sample * g).coerceIn(-1f, 1f))
                    i += 4
                }
                inputBuffer.position(end)
            }
            else -> {
                // Unknown encoding: pass through unchanged rather than corrupt it.
                output.put(inputBuffer)
            }
        }
        output.flip()
    }

    private companion object {
        const val MIN_16 = -32768
        const val MAX_16 = 32767
    }
}
