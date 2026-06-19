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

    private val castListener = object : Cast.Listener() {
        override fun onVolumeChanged() {
            publishCastVolume()
        }

        override fun onDeviceNameChanged() {
            publishCastVolume()
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i(TAG, "Cast session started id=$sessionId")
            onSessionAvailable(session)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.i(TAG, "Cast session resumed suspended=$wasSuspended")
            onSessionAvailable(session)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.i(TAG, "Cast session ended error=$error")
            onSessionUnavailable()
        }

        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session start failed error=$error")
        }

        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session resume failed error=$error")
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.i(TAG, "Cast session suspended reason=$reason")
            onSessionUnavailable()
        }
    }

    private val availabilityListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
            Log.i(TAG, "CastPlayer reports session available")
            onSessionAvailable(castContext?.sessionManager?.currentCastSession)
        }

        override fun onCastSessionUnavailable() {
            Log.i(TAG, "CastPlayer reports session unavailable")
            onSessionUnavailable()
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
        castPlayer = CastPlayer(ctx).also { player ->
            player.setSessionAvailabilityListener(availabilityListener)
            playback.attachCastPlayer(player)
            if (player.isCastSessionAvailable) onSessionAvailable(ctx.sessionManager.currentCastSession)
        }
    }

    private fun onSessionAvailable(session: CastSession?) {
        attachVolumeSession(session)
        playback.onCastSessionAvailable()
    }

    private fun onSessionUnavailable() {
        detachVolumeSession()
        playback.onCastSessionUnavailable()
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
