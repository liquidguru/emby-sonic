package guru.liquid.embysonic.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import guru.liquid.embysonic.BuildConfig
import guru.liquid.embysonic.data.settings.AppSettings
import guru.liquid.embysonic.data.settings.SettingsRepository
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the text a tester can send when something is wrong, so nobody needs adb:
 * app and device versions, what kind of server address is configured, and the
 * on-device failure log. Written to the cache dir and handed out through the
 * FileProvider, so the share sheet can attach it to mail or chat.
 *
 * The server's address is hidden unless the user opts in. Testers paste these
 * files into public threads, and the host also appears inside the error text
 * ("Unable to resolve host ..."), so hiding it only in the header would not do.
 * What helps diagnosis is the kind of address (https or not, port, IP or domain),
 * and that is kept.
 */
@Singleton
class DiagnosticsReport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val errorLog: PlaybackErrorLog,
) {
    /** Writes the report and returns a content Uri for it. */
    fun write(includeAddress: Boolean = false): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, FILE_NAME)
        file.writeText(redact(build(includeAddress)))
        return FileProvider.getUriForFile(context, "${context.packageName}.widgetart", file)
    }

    private fun build(includeAddress: Boolean): String {
        val snap = settings.snapshot()
        val log = errorLog.readText().let { if (includeAddress) it else hideHosts(it, snap) }
        return buildString {
            appendLine("liquidWave diagnostics")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Emby server: ${describe(snap.serverUrl, includeAddress)}")
            appendLine("Coordinator: ${describe(snap.coordinatorUrl, includeAddress)}")
            appendLine("Server address: ${if (includeAddress) "included" else "hidden"}")
            appendLine()
            appendLine("--- Recent failures (newest last) ---")
            appendLine(log.ifBlank { "(none recorded)" })
        }
    }

    /** "https, port 8096, public domain name", or the full origin when the user opted in. */
    private fun describe(url: String?, includeAddress: Boolean): String {
        if (url.isNullOrBlank()) return "(not set)"
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "(unreadable)"
        val host = uri.host ?: return "(unreadable)"
        val kind = kindOf(host)
        val port = if (uri.port > 0) uri.port else null
        return if (includeAddress) {
            "${uri.scheme}://$host${port?.let { ":$it" }.orEmpty()} ($kind)"
        } else {
            "${uri.scheme}, ${port?.let { "port $it" } ?: "default port"}, $kind"
        }
    }

    private fun kindOf(host: String): String {
        if (IPV4.matches(host)) return if (isPrivateIpv4(host)) "LAN IP address" else "public IP address"
        val lower = host.lowercase()
        val local = !lower.contains('.') ||
            LOCAL_SUFFIXES.any { lower.endsWith(it) }
        return if (local) "local name" else "public domain name"
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) ||
            p[0] == 127 || (p[0] == 169 && p[1] == 254)
    }

    /** Replace the configured hosts (longest first), then any stray IPv4 address. */
    private fun hideHosts(text: String, snap: AppSettings): String {
        val hosts = listOf(
            snap.serverUrl to "<server>",
            snap.coordinatorUrl to "<coordinator>",
            snap.castServerUrl to "<cast-server>",
        ).mapNotNull { (url, label) ->
            url?.takeIf { it.isNotBlank() }
                ?.let { runCatching { URI(it.trim()).host }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?.let { it to label }
        }.sortedByDescending { it.first.length }
        var out = text
        hosts.forEach { (host, label) -> out = out.replace(host, label, ignoreCase = true) }
        return IPV4_ANYWHERE.replace(out, "<ip>")
    }

    internal companion object {
        private const val DIR = "diagnostics"
        private const val FILE_NAME = "liquidwave-diagnostics.txt"
        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain")
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IPV4_ANYWHERE = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")

        // Error messages can carry a request URL or header; strip the value after
        // anything token-shaped. Needs a = or : so plain prose ("token expired") survives.
        private val SECRET = Regex(
            "(?i)(api_key|apikey|x-emby-token|x-mediabrowser-token|access_token|token|password|pw)" +
                "([\"']?\\s*[=:]\\s*[\"']?)[^&\\s\"',}]+",
        )

        fun redact(text: String): String =
            SECRET.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
    }
}
