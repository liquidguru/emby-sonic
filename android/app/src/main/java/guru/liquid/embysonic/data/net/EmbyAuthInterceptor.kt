package guru.liquid.embysonic.data.net

import android.os.Build
import guru.liquid.embysonic.BuildConfig
import guru.liquid.embysonic.data.settings.SettingsRepository
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Emby's auth headers to every request:
 *  - `X-Emby-Authorization` carries the client/device context Emby requires even for
 *    AuthenticateByName (before any token exists).
 *  - `X-Emby-Token` is added once a session token is available.
 */
class EmbyAuthInterceptor(
    private val settings: SettingsRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val snap = settings.snapshot()
        val authHeader = buildString {
            append("MediaBrowser ")
            append("Client=\"liquidWave\", ")
            append("Device=\"${Build.MODEL}\", ")
            append("DeviceId=\"${snap.deviceId}\", ")
            append("Version=\"${BuildConfig.VERSION_NAME}\"")
        }

        val builder = chain.request().newBuilder()
            .header("X-Emby-Authorization", authHeader)
            .header("Accept", "application/json")

        snap.accessToken?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-Emby-Token", it)
        }

        return chain.proceed(builder.build())
    }
}
