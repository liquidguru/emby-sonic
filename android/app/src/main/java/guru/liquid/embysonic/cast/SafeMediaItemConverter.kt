package guru.liquid.embysonic.cast

import android.util.Log
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaQueueItem
import androidx.media3.common.MediaMetadata as Media3Metadata
import com.google.android.gms.cast.MediaMetadata as CastMetadata

/**
 * A [MediaItemConverter] that cannot crash the app on a receiver status update.
 *
 * Media3's [DefaultMediaItemConverter] round-trips a MediaItem through custom
 * data it attaches to the queue item, and asserts that data is present when
 * converting back. Any queue item the receiver reports that we did not put there
 * — which is what a mid-queue removal produces — has no custom data, so the
 * assert throws NullPointerException. That happens on the main thread inside
 * CastPlayer.updateTimeline, via the Cast SDK's status listener, so there is
 * nowhere for us to catch it: the app dies (issue seen 2026-08-19, twice in
 * 15 seconds — relaunch re-synced the queue and crashed again).
 *
 * The same throw also leaves the timeline unbuilt, which is why the progress
 * counter freezes while audio keeps playing: the receiver goes on streaming
 * regardless, but position has nothing to advance against.
 *
 * So conversion degrades instead of throwing. A MediaItem rebuilt from
 * [com.google.android.gms.cast.MediaInfo] loses our extras, but it carries the
 * id, uri and display metadata — enough for a correct timeline and a sane
 * now-playing — and playback continues.
 *
 * DefaultMediaItemConverter is final, hence delegation rather than a subclass.
 *
 * NOTE: this exists so the Cast crash could be fixed WITHOUT a Media3 version
 * bump. Media3 sits underneath crossfade, which has a long history of subtle
 * bugs, and a bump needs real on-device crossfade re-verification. If Media3 is
 * ever upgraded, re-check whether the upstream converter still asserts here
 * before assuming this is dead weight.
 */
class SafeMediaItemConverter(
    private val delegate: MediaItemConverter = DefaultMediaItemConverter(),
) : MediaItemConverter {

    /** Outgoing items are ours, so they always carry the custom data. */
    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem =
        delegate.toMediaQueueItem(mediaItem)

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
        runCatching { delegate.toMediaItem(mediaQueueItem) }
            .getOrElse { error ->
                // Deliberately broad: ANY failure here would otherwise reach the
                // Cast SDK's listener on the main thread and kill the process.
                Log.w(TAG, "Cast queue item lacked our custom data; rebuilding from MediaInfo", error)
                mediaQueueItem.fromMediaInfo()
            }

    private fun MediaQueueItem.fromMediaInfo(): MediaItem {
        val info = media
        val castMetadata = info?.metadata
        val builder = MediaItem.Builder()
        info?.contentId?.let(builder::setMediaId)
        (info?.contentUrl ?: info?.contentId)?.let(builder::setUri)
        info?.contentType?.let(builder::setMimeType)
        return builder
            .setMediaMetadata(
                Media3Metadata.Builder()
                    .setTitle(castMetadata?.getString(CastMetadata.KEY_TITLE))
                    .setArtist(castMetadata?.getString(CastMetadata.KEY_ARTIST))
                    .setAlbumTitle(castMetadata?.getString(CastMetadata.KEY_ALBUM_TITLE))
                    .build(),
            )
            .build()
    }

    private companion object {
        const val TAG = "SafeMediaItemConverter"
    }
}
