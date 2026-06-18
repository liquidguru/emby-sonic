package guru.liquid.embysonic.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class AvrcpDurationPlayer(
    player: Player,
    private val fallbackDurationMs: () -> Long?,
    private val positionMs: () -> Long?,
    private val bufferedPositionMs: () -> Long?,
    private val onSeekToMs: (Long) -> Unit,
) : ForwardingPlayer(player) {
    override fun getMediaMetadata(): MediaMetadata {
        val metadata = super.getMediaMetadata()
        val metadataDuration = metadata.durationMs?.takeIf { it > 0L }
        val fallbackDuration = fallbackDurationMs()?.takeIf { it > 0L }
        val duration = metadataDuration ?: fallbackDuration
            ?: return metadata
        return MediaMetadata.Builder()
            .populate(metadata)
            .setDurationMs(duration)
            .build()
    }

    override fun getDuration(): Long {
        val duration = super.getDuration()
        return duration.withFallback()
    }

    override fun getContentDuration(): Long = super.getContentDuration().withFallback()

    override fun getCurrentPosition(): Long = positionMs()?.takeIf { it >= 0L } ?: super.getCurrentPosition()

    override fun getContentPosition(): Long = getCurrentPosition()

    override fun getBufferedPosition(): Long = bufferedPositionMs()?.takeIf { it >= 0L } ?: super.getBufferedPosition()

    override fun getContentBufferedPosition(): Long = getBufferedPosition()

    override fun seekTo(positionMs: Long) {
        onSeekToMs(positionMs.coerceAtLeast(0L))
    }

    private fun Long.withFallback(): Long =
        if (this != C.TIME_UNSET && this > 0L) {
            this
        } else {
            fallbackDurationMs()?.takeIf { it > 0L } ?: this
        }

}
