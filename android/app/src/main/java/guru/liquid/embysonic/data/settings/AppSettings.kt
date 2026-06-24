package guru.liquid.embysonic.data.settings

/**
 * Snapshot of all persisted app settings.
 *
 * [serverUrl] is the Emby server base URL (e.g. http://192.168.1.50:8096).
 * [coordinatorUrl] is the Emby Sonic coordinator base URL (e.g. http://192.168.1.50:8765);
 * it may live on a different host/port than Emby, so it is stored independently.
 */
data class AppSettings(
    val serverUrl: String? = null,
    val coordinatorUrl: String? = null,
    val accessToken: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val deviceId: String,
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationMs: Int = 6_000,
    val eqEnabled: Boolean = false,
    // Per-band gains in millibels, indexed by equalizer band; empty = flat/default.
    val eqBandLevels: List<Int> = emptyList(),
    // Active built-in preset index, or -1 for a manual/custom curve.
    val eqPreset: Int = -1,
    val generatedMixTracks: Int = 25,
    val audiobookSpeed: Float = 1f,
    val themeChoice: ThemeChoice = ThemeChoice.DEFAULT,
    // Direct LAN Emby URL (e.g. http://192.168.1.50:8096) used only for casting:
    // cast receivers fetch the stream themselves and accept cleartext http on the
    // local network, whereas the remote/reverse-proxied serverUrl may not be
    // reachable or cert-valid for them. Falls back to serverUrl when blank.
    val castServerUrl: String? = null,
) {
    val isLoggedIn: Boolean
        get() = !serverUrl.isNullOrBlank() && !accessToken.isNullOrBlank()
}
