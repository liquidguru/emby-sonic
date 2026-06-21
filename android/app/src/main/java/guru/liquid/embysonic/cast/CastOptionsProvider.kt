package guru.liquid.embysonic.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Required by the Cast framework (referenced from the manifest meta-data). Uses
 * Google's Default Media Receiver — no Cast Developer Console registration or
 * App ID needed. A branded (Styled) receiver can be swapped in later by changing
 * only the receiver application id here.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        // The app's Media3 MediaLibrarySession owns the notification + lock-screen
        // controls while casting (its player is swapped to the CastPlayer, so the
        // shade already shows the casting track with play/pause + seek). Disable the
        // Cast framework's own notification AND media session so the shade shows a
        // single media card instead of two duplicates for the same track.
        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(null)
            .setMediaSessionEnabled(false)
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
