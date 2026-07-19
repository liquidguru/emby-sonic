package guru.liquid.embysonic.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import guru.liquid.embysonic.MainActivity
import guru.liquid.embysonic.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the process alive while downloads run, so a large
 * download (an audiobook especially) isn't killed when the app is backgrounded and
 * Android reclaims the process (#45). It does NOT do the downloading —
 * [PlaylistDownloader] owns that on its own coroutine; this only holds the process
 * up and shows an ongoing progress notification, then stops itself the moment no
 * download is active.
 */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var downloader: PlaylistDownloader
    @Inject lateinit var store: DownloadStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sawActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Track active downloads and reflect their progress; stop once idle.
        scope.launch {
            combine(downloader.activeDownloads, store.state) { active, index -> active to index }
                .collect { (active, index) ->
                    if (active.isNotEmpty()) {
                        sawActive = true
                        notify(progressNotification(active, index))
                    } else if (sawActive) {
                        stop()
                    }
                }
        }
        // Safety net: if a download never materialises (e.g. it failed to resolve
        // before flagging active), don't sit as a foreground service forever.
        scope.launch {
            delay(GRACE_MS)
            if (!sawActive) stop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground within ~5s of startForegroundService.
        startForegroundCompat(
            progressNotification(downloader.activeDownloads.value, store.state.value),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: android.app.Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun progressNotification(
        active: Set<String>,
        index: DownloadIndex,
    ): android.app.Notification {
        val playlists = index.playlists.filter { it.playlistId in active }
        val (title, done, total) = when {
            playlists.isEmpty() -> Triple("Preparing download…", 0, 0)
            playlists.size == 1 -> {
                val p = playlists.first()
                Triple("Downloading ${p.name}", p.completeCount, p.tracks.size)
            }
            else -> Triple(
                "Downloading ${playlists.size} items",
                playlists.sumOf { it.completeCount },
                playlists.sumOf { it.tracks.size },
            )
        }
        val indeterminate = total <= 0
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download_done)
            .setContentTitle(title)
            .setContentText(if (indeterminate) null else "$done of $total tracks")
            .setProgress(total.coerceAtLeast(0), done, indeterminate)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Download progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Ongoing progress while downloads are running." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "download_progress"
        private const val NOTIFICATION_ID = 2001
        private const val GRACE_MS = 10_000L

        /** Start from a FOREGROUND context (a user's download tap). */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
