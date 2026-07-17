package guru.liquid.embysonic.domain

import guru.liquid.embysonic.data.emby.EmbyApi
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.dto.AuthenticateRequest
import guru.liquid.embysonic.data.settings.SettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val embyApi: EmbyApi,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
) {
    /**
     * Logs into Emby and persists the session. The Emby base URL must be saved
     * *before* the auth call so the BaseUrlInterceptor can route it.
     */
    suspend fun login(
        rawServerUrl: String,
        username: String,
        password: String,
    ): Result<Unit> = runCatching {
        val serverUrl = normalizeUrl(rawServerUrl)
            ?: throw IllegalArgumentException("Invalid server URL")

        // Seed device id + provisional server URL so the interceptors can route.
        settings.ensureDeviceId()
        settings.saveSession(
            serverUrl = serverUrl,
            accessToken = "",
            userId = "",
            userName = "",
            coordinatorUrl = existingCoordinatorUrl(),
        )

        val resp = embyApi.authenticateByName(AuthenticateRequest(username, password))

        settings.saveSession(
            serverUrl = serverUrl,
            accessToken = resp.accessToken,
            userId = resp.user.id,
            userName = resp.user.name,
            coordinatorUrl = existingCoordinatorUrl(),
        )
    }

    /**
     * The coordinator URL is NOT guessed from the Emby host. It used to be derived as
     * `scheme://embyhost:8765`, which is only right when the coordinator runs on the
     * Emby box AND you signed in over the LAN. For anyone using an external address
     * it produced a confidently wrong value — and a *pre-filled* wrong value is worse
     * than an empty one: an empty field asks to be filled, whereas a plausible one
     * reads as already configured, so the user leaves it alone and concludes the app
     * is broken. Blank is a state the app handles honestly (BaseUrlInterceptor throws
     * "No server URL configured", which the UI reports as "can't reach the backend"),
     * so leave it blank until the user has a coordinator to point at.
     *
     * Keeps any URL already saved, so re-authenticating doesn't wipe a working setup.
     */
    private fun existingCoordinatorUrl(): String = settings.snapshot().coordinatorUrl.orEmpty()

    suspend fun logout() {
        library.invalidateBrowseCache()
        settings.clearSession()
    }

    /**
     * Adds a scheme if missing and strips a trailing slash. Returns null if
     * unparseable. Defaults to **https** for a bare host so credentials aren't sent
     * in cleartext by accident; a local server without TLS still works if the user
     * types an explicit `http://` URL.
     *
     * Scheme matching is case-INSENSITIVE: schemes are case-insensitive per RFC 3986,
     * and Android's keyboard capitalises the first letter of a field by default, so
     * "Https://host" is what a phone actually produces. Matching case-sensitively
     * sent it down the no-scheme path and built "https://Https://host", which failed
     * to parse — the URL was rejected for being valid.
     */
    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        // Re-render from the parsed URL so the stored value is canonical whatever the
        // keyboard did to it ("HTTPS://example.com" -> "https://example.com").
        // HttpUrl always renders a root path, hence the trailing-slash trim.
        return url.toString().trimEnd('/')
    }
}
