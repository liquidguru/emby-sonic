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
            // Make "Stop casting" actually stop the receiver. This defaults to
            // FALSE in the Cast SDK — CastOptions.Builder never initialises the
            // field — which means ending a session only disconnects the *sender*
            // and leaves the receiver application running with the queue still
            // loaded and playing. The speaker carries on, orphaned: the phone
            // correctly shows it is no longer casting while the music keeps going,
            // and there is then no sender left to stop it from.
            //
            // It compounds, because the app joins any live session on startup (see
            // CastManager.initialize). So the next device to open liquidWave — or
            // the same one, later — silently adopts the abandoned session and
            // becomes its remote, playing a queue the user thought they had ended.
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    private companion object {
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
    }
}
