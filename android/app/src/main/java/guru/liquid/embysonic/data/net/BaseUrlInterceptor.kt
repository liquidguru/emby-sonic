package guru.liquid.embysonic.data.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Rebuilds every outgoing request on the base URL returned by [baseUrlProvider],
 * preserving an optional base path and appending Retrofit's encoded request path and
 * query. The provider is read fresh on each call so a user changing the server
 * address at runtime takes effect immediately.
 */
class BaseUrlInterceptor(
    private val baseUrlProvider: () -> String?,
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestUrl = request.url
        val base = baseUrlProvider()?.toHttpUrlOrNull()
            ?: throw IOException("No server URL configured")

        val newUrl = base.newBuilder()
            .apply {
                for (segment in requestUrl.encodedPathSegments) {
                    if (segment.isNotEmpty()) addEncodedPathSegment(segment)
                }
                encodedQuery(requestUrl.encodedQuery)
            }
            .build()

        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
