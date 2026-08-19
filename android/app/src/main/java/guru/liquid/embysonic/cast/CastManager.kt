package guru.liquid.embysonic.cast

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import guru.liquid.embysonic.playback.PlaybackController
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class CastManager @Inject constructor(
    private val playback: PlaybackController,
) {
    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null
    private var volumeSession: CastSession? = null
    private var userEndingSession = false

    private val castListener = object : Cast.Listener() {
        override fun onVolumeChanged() {
            publishCastVolume()
        }

        override fun onDeviceNameChanged() {
            publishCastVolume()
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        // Only attach volume here. The local->remote media handoff is driven by
        // the CastPlayer's onCastSessionAvailable, which fires once Media3's
        // CastPlayer is actually wired to the receiver — loading media on the
        // earlier session-started callback lands on a not-ready player ("no media
        // selected" on the receiver).
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i(TAG, "Cast session started id=$sessionId")
            attachVolumeSession(session)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.i(TAG, "Cast session resumed suspended=$wasSuspended")
            attachVolumeSession(session)
        }

        // error == 0 is a clean, user-initiated stop from some Cast surfaces.
        // Other surfaces can report a non-zero framework code after first firing
        // onSessionEnding; treat that callback as the user intent. Abnormal
        // network loss should end without onSessionEnding, so it hands back paused
        // and never doubles audio with a still-playing receiver.
        override fun onSessionEnded(session: CastSession, error: Int) {
            val resumePlayback = error == 0 || userEndingSession
            Log.i(TAG, "Cast session ended error=$error userEnding=$userEndingSession resumePlayback=$resumePlayback")
            userEndingSession = false
            onCastDisconnected(resumePlayback = resumePlayback)
        }

        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session start failed error=$error")
        }

        override fun onSessionEnding(session: CastSession) {
            Log.i(TAG, "Cast session ending by user")
            userEndingSession = true
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

        // A failed resume is a genuine, permanent loss — hand back to local.
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session resume failed error=$error")
            userEndingSession = false
            onCastDisconnected(resumePlayback = false)
        }

        // A suspend is a TRANSIENT drop (network blip): the receiver keeps playing
        // and the session will resume. Do NOT hand playback back to the phone here
        // — that would play the same audio on the phone on top of the receiver.
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.i(TAG, "Cast session suspended reason=$reason (keeping cast; awaiting resume)")
            userEndingSession = false
            onCastSuspended()
        }
    }

    private val availabilityListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
            Log.i(TAG, "CastPlayer reports session available")
            onCastConnected(castContext?.sessionManager?.currentCastSession)
        }

        // This also fires on a transient suspend, so it must NOT trigger the
        // remote->local handoff. The authoritative "casting is over" signal is the
        // SessionManagerListener's onSessionEnded / onSessionResumeFailed.
        override fun onCastSessionUnavailable() {
            Log.i(TAG, "CastPlayer reports session unavailable (no handoff; awaiting session end)")
            detachVolumeSession()
        }
    }

    /** Safe to call when Play Services is missing — it simply no-ops. */
    fun initialize(context: Context) {
        if (castPlayer != null) return
        if (!playServicesAvailable(context)) {
            Log.i(TAG, "Google Play Services unavailable; Cast disabled")
            return
        }
        val ctx = runCatching { CastContext.getSharedInstance(context) }
            .onFailure { Log.w(TAG, "CastContext init failed", it) }
            .getOrNull() ?: return
        castContext = ctx
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        playback.setCastVolumeController(::setCastVolume)
        // Not CastPlayer(ctx): the stock converter throws on a queue item that
        // carries no custom data, which a mid-queue removal produces. See
        // SafeMediaItemConverter.
        castPlayer = CastPlayer(ctx, SafeMediaItemConverter()).also { player ->
            player.setSessionAvailabilityListener(availabilityListener)
            playback.attachCastPlayer(player)
            if (player.isCastSessionAvailable) onCastConnected(ctx.sessionManager.currentCastSession)
        }
    }

    private fun onCastConnected(session: CastSession?) {
        attachVolumeSession(session)
        playback.onCastSessionAvailable()
    }

    /**
     * Genuine end of casting — hand playback back to the local player. [resumePlayback]
     * is true only for a clean, user-initiated stop; on an abnormal/network end it's
     * false so the phone doesn't double up audio with a still-playing receiver.
     */
    private fun onCastDisconnected(resumePlayback: Boolean) {
        detachVolumeSession()
        playback.onCastSessionUnavailable(resumePlayback)
    }

    /**
     * Transient suspend (network blip). The receiver keeps playing and the session
     * is expected to resume, so we keep the CastPlayer as the active player and only
     * hide the in-app volume control until [onCastConnected] fires again. If the
     * drop turns out to be permanent, onSessionEnded/onSessionResumeFailed will hand
     * back to local.
     */
    private fun onCastSuspended() {
        detachVolumeSession()
    }

    private fun attachVolumeSession(session: CastSession?) {
        if (session == null) {
            playback.onCastVolumeUnavailable()
            return
        }
        if (volumeSession === session) {
            publishCastVolume()
            return
        }
        detachVolumeSession()
        volumeSession = session
        session.addCastListener(castListener)
        publishCastVolume()
    }

    private fun detachVolumeSession() {
        volumeSession?.removeCastListener(castListener)
        volumeSession = null
        playback.onCastVolumeUnavailable()
    }

    private fun publishCastVolume() {
        val session = volumeSession ?: return
        val volume = runCatching { session.volume }.getOrNull()
        val deviceName = runCatching {
            session.castDevice?.friendlyName ?: session.castDevice?.modelName
        }.getOrNull()
        playback.onCastVolumeChanged(volume = volume, deviceName = deviceName)
    }

    private fun setCastVolume(volume: Float) {
        val session = volumeSession ?: castContext?.sessionManager?.currentCastSession
        if (session == null) {
            playback.onCastVolumeUnavailable()
            return
        }
        runCatching { session.setVolume(volume.coerceIn(0f, 1f).toDouble()) }
            .onFailure {
                Log.w(TAG, "Cast volume update failed", it)
                playback.onCastVolumeSetFailed()
            }
    }

    private fun playServicesAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private companion object {
        const val TAG = "CastManager"
    }
}
