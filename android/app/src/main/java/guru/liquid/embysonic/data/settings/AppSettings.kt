package guru.liquid.embysonic.data.settings

/**
 * Snapshot of all persisted app settings.
 *
 * [serverUrl] is the Emby server base URL (e.g. http://192.168.1.9:8096).
 * [coordinatorUrl] is the Emby Sonic coordinator base URL (e.g. http://192.168.1.9:8765);
 * it may live on a different host/port than Emby, so it is stored independently.
 */
data class AppSettings(
    val serverUrl: String? = null,
    val coordinatorUrl: String? = null,
    val accessToken: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val deviceId: String,
) {
    val isLoggedIn: Boolean
        get() = !serverUrl.isNullOrBlank() && !accessToken.isNullOrBlank()
}
