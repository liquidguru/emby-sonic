package guru.liquid.embysonic.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Required by the Cast framework (referenced from the manifest meta-data). Uses
 * Google's Default Media Receiver — no Cast Developer Console registration or
 * App ID needed. A branded (Styled) receiver can be swapped in later by changing
 * only the receiver application id here.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            // Google's well-known Default Media Receiver app id (no registration).
            .setReceiverApplicationId(DEFAULT_MEDIA_RECEIVER_APP_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    private companion object {
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
    }
}
