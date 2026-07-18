package guru.liquid.embysonic.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import guru.liquid.embysonic.MainActivity
import guru.liquid.embysonic.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "download complete" notification. This is a NON-foreground notification
 * (downloads run on a background coroutine, not a service), so unlike the media
 * notification it genuinely needs POST_NOTIFICATIONS on Android 13+ — which is the
 * one honest reason liquidWave asks for that permission. [areNotificationsEnabled]
 * gates the post, so a denied permission is a clean no-op rather than a crash or a
 * silently-dropped notify.
 */
@Singleton
class DownloadNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Tells you when an offline download finishes." }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Notify that a download bundle finished. [completed]/[total] describe the
     * outcome so a partial or failed download reads honestly rather than as a
     * blanket "complete".
     */
    fun notifyDownloadFinished(name: String, completed: Int, total: Int) {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return

        val (title, text) = when {
            completed == 0 ->
                "Download failed" to "Couldn't download \"$name\""
            completed < total ->
                "Download finished" to "\"$name\" — $completed of $total tracks downloaded"
            else ->
                "Download complete" to "\"$name\" is ready for offline listening"
        }

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Keyed by name so concurrent downloads don't overwrite each other's result.
        notifications.notify(name.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
    }
}
