package guru.liquid.embysonic.data.net

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight current-connectivity checks for download gating. */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivity: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    /**
     * True when the active network is metered (typically cellular, or a metered
     * hotspot). Used to honour a "download over Wi-Fi only" preference. Defaults to
     * metered=true when connectivity can't be read, so we err toward not using data.
     */
    fun isActiveNetworkMetered(): Boolean = connectivity?.isActiveNetworkMetered ?: true
}
