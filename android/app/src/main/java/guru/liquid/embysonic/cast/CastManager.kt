package guru.liquid.embysonic.cast

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.util.UnstableApi
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

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i(TAG, "Cast session started id=$sessionId")
            playback.onCastSessionAvailable()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.i(TAG, "Cast session resumed suspended=$wasSuspended")
            playback.onCastSessionAvailable()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.i(TAG, "Cast session ended error=$error")
            playback.onCastSessionUnavailable()
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
            playback.onCastSessionUnavailable()
        }
    }

    private val availabilityListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
            Log.i(TAG, "CastPlayer reports session available")
            playback.onCastSessionAvailable()
        }

        override fun onCastSessionUnavailable() {
            Log.i(TAG, "CastPlayer reports session unavailable")
            playback.onCastSessionUnavailable()
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
        castPlayer = CastPlayer(ctx).also { player ->
            player.setSessionAvailabilityListener(availabilityListener)
            playback.attachCastPlayer(player)
            if (player.isCastSessionAvailable) playback.onCastSessionAvailable()
        }
    }

    private fun playServicesAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private companion object {
        const val TAG = "CastManager"
    }
}
