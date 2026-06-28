package guru.liquid.embysonic.domain

import guru.liquid.embysonic.data.emby.EmbyApi
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.dto.AuthenticateRequest
import guru.liquid.embysonic.data.settings.SettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Default port the coordinator listens on; used to derive its URL from the Emby host. */
private const val DEFAULT_COORDINATOR_PORT = 8765

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
            coordinatorUrl = deriveCoordinatorUrl(serverUrl),
        )

        val resp = embyApi.authenticateByName(AuthenticateRequest(username, password))

        settings.saveSession(
            serverUrl = serverUrl,
            accessToken = resp.accessToken,
            userId = resp.user.id,
            userName = resp.user.name,
            coordinatorUrl = deriveCoordinatorUrl(serverUrl),
        )
    }

    suspend fun logout() {
        library.invalidateBrowseCache()
        settings.clearSession()
    }

    private fun deriveCoordinatorUrl(serverUrl: String): String {
        val url = serverUrl.toHttpUrlOrNull() ?: return serverUrl
        return "${url.scheme}://${url.host}:$DEFAULT_COORDINATOR_PORT"
    }

    /**
     * Adds a scheme if missing and strips a trailing slash. Returns null if
     * unparseable. Defaults to **https** for a bare host so credentials aren't sent
     * in cleartext by accident; a local server without TLS still works if the user
     * types an explicit `http://` URL.
     */
    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return if (withScheme.toHttpUrlOrNull() != null) withScheme else null
    }
}
