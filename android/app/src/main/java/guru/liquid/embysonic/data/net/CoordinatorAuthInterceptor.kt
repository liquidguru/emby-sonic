package guru.liquid.embysonic.data.net

import guru.liquid.embysonic.data.settings.SettingsRepository
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `X-Emby-Token` to coordinator requests. The coordinator validates this token
 * against Emby's `/System/Info`, so the same Emby session token authenticates both
 * services.
 */
class CoordinatorAuthInterceptor(
    private val settings: SettingsRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = settings.snapshot().accessToken
        val builder = chain.request().newBuilder()
            .header("Accept", "application/json")
        token?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-Emby-Token", it)
        }
        return chain.proceed(builder.build())
    }
}
