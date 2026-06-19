package guru.liquid.embysonic.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import guru.liquid.embysonic.MainActivity

/**
 * Required by the Cast framework (referenced from the manifest meta-data). Uses
 * Google's Default Media Receiver — no Cast Developer Console registration or
 * App ID needed. A branded (Styled) receiver can be swapped in later by changing
 * only the receiver application id here.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        // Explicit notification + lock-screen controls for the cast session, so the
        // shade always shows a play/pause + stop-casting card while casting.
        val notificationOptions = NotificationOptions.Builder()
            .setActions(
                listOf(
                    MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                    MediaIntentReceiver.ACTION_STOP_CASTING,
                ),
                intArrayOf(0, 1),
            )
            .setTargetActivityClassName(MainActivity::class.java.name)
            .build()
        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .build()

        return CastOptions.Builder()
            // Google's well-known Default Media Receiver app id (no registration).
            .setReceiverApplicationId(DEFAULT_MEDIA_RECEIVER_APP_ID)
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    private companion object {
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
    }
}
