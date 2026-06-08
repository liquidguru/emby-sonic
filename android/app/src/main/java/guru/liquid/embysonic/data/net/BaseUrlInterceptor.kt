package guru.liquid.embysonic.data.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Rewrites the scheme/host/port of every outgoing request to the base URL returned
 * by [baseUrlProvider], which is read fresh on each call so a user changing the
 * server address at runtime takes effect immediately. The Retrofit interfaces use a
 * placeholder base URL ("http://localhost/"); this interceptor redirects to the real
 * target. The request path supplied by Retrofit is preserved.
 */
class BaseUrlInterceptor(
    private val baseUrlProvider: () -> String?,
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val base = baseUrlProvider()?.toHttpUrlOrNull()
            ?: throw IOException("No server URL configured")

        val newUrl = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()

        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
