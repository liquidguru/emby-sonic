package guru.liquid.embysonic.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import dagger.hilt.android.qualifiers.ApplicationContext
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.playback.PlaybackController
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 0 SPIKE — proves the riskiest cast unknowns (cast-safe Emby stream URL,
 * query-param auth, and a Default-Media-Receiver-playable format) before the
 * Phase 1 active-player refactor.
 *
 * When a Cast session connects, it pauses local playback and loads the *current*
 * track onto the cast device via [com.google.android.gms.cast.framework.media.RemoteMediaClient].
 * It does NOT yet do queue handoff, transport routing, progress reporting, or
 * disconnect handover — those are Phase 1+. See docs/codex-cast.md.
 */
@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
) {
    private var castContext: CastContext? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = castCurrentTrack(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = castCurrentTrack(session)
        override fun onSessionEnded(session: CastSession, error: Int) = Unit
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    /** Safe to call when Play Services is missing — it simply no-ops. */
    fun initialize(context: Context) {
        if (!playServicesAvailable(context)) {
            Log.i(TAG, "Google Play Services unavailable; Cast disabled")
            return
        }
        val ctx = runCatching { CastContext.getSharedInstance(context) }
            .onFailure { Log.w(TAG, "CastContext init failed", it) }
            .getOrNull() ?: return
        castContext = ctx
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
    }

    private fun castCurrentTrack(session: CastSession) {
        val track = playback.state.value.currentTrack ?: run {
            Log.i(TAG, "Cast session started with nothing playing")
            return
        }
        val client = session.remoteMediaClient ?: return
        val url = castStreamUrl(track.id) ?: return
        val positionMs = playback.state.value.positionMs

        playback.pause()

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, track.title)
            track.artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
            track.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
            // The receiver fetches artwork itself, so the URL needs the token as a
            // query param (the in-app loader uses an auth header the receiver can't send).
            castImageUrl(track.imageUrl)?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(CAST_MIME)
            .setContentUrl(url)
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(positionMs.coerceAtLeast(0))
            .build()
        Log.d(TAG, "Casting ${track.id} -> $url")
        client.load(request)
    }

    /**
     * Build a self-contained Emby stream URL the cast receiver can fetch on its
     * own: LAN http base, token as the `api_key` query param (the receiver can't
     * send our X-Emby-Token header), forced to mp3 so the Default Media Receiver
     * can decode it (it can't reliably do FLAC).
     */
    private fun castStreamUrl(itemId: String): String? {
        val snap = settings.snapshot()
        val base = snap.serverUrl?.trimEnd('/') ?: return null
        val token = snap.accessToken ?: return null
        val userId = snap.userId ?: return null
        return Uri.parse("$base/Audio/${Uri.encode(itemId)}/universal")
            .buildUpon()
            .appendQueryParameter("UserId", userId)
            .appendQueryParameter("DeviceId", snap.deviceId)
            .appendQueryParameter("MaxStreamingBitrate", "140000000")
            .appendQueryParameter("Container", "mp3")
            .appendQueryParameter("AudioCodec", "mp3")
            .appendQueryParameter("TranscodingContainer", "mp3")
            .appendQueryParameter("TranscodingProtocol", "http")
            .appendQueryParameter("api_key", token)
            .appendQueryParameter("PlaySessionId", UUID.randomUUID().toString())
            .build()
            .toString()
    }

    /** Append the Emby token so the cast receiver can fetch artwork without our auth header. */
    private fun castImageUrl(imageUrl: String?): String? {
        val url = imageUrl ?: return null
        val token = settings.snapshot().accessToken ?: return url
        if (url.contains("api_key=")) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}api_key=$token"
    }

    private fun playServicesAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private companion object {
        const val TAG = "CastManager"
        const val CAST_MIME = "audio/mpeg"
    }
}
