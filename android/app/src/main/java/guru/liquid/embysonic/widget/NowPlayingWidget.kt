package guru.liquid.embysonic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import guru.liquid.embysonic.MainActivity
import guru.liquid.embysonic.R
import guru.liquid.embysonic.playback.PlaybackUiState

/**
 * Renders the Now Playing home-screen widget. Display is driven from
 * [PlaybackController][guru.liquid.embysonic.playback.PlaybackController]'s
 * state flow (see `startWidgetUpdates`), and the transport buttons fire
 * broadcasts back to [NowPlayingWidgetProvider], which routes them through the
 * same `PlaybackController` the in-app UI and notification use.
 */
object NowPlayingWidget {

    /** The minimal slice of playback state the widget actually renders. */
    data class Snapshot(
        val hasContent: Boolean,
        val title: String,
        val artist: String,
        val imageUrl: String?,
        val isPlaying: Boolean,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
        // Position/duration in whole seconds so a 500ms tick only re-renders the
        // widget about once per second instead of twice.
        val positionSec: Long,
        val durationSec: Long,
        val isCasting: Boolean,
        val castDeviceName: String?,
    )

    fun snapshotFrom(state: PlaybackUiState): Snapshot {
        val track = state.currentTrack
        return Snapshot(
            hasContent = track != null,
            title = track?.title ?: "liquidWave",
            artist = track?.artist?.takeIf { it.isNotBlank() } ?: "Tap to open",
            imageUrl = track?.imageUrl,
            isPlaying = state.isPlaying,
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
            positionSec = (state.positionMs / 1000L).coerceAtLeast(0),
            durationSec = (state.durationMs / 1000L).coerceAtLeast(0),
            isCasting = state.isCasting,
            castDeviceName = state.castVolume.deviceName,
        )
    }

    /**
     * Full widget update — includes the artwork bitmap. Use this only when the
     * track/state changes, not on every position tick: re-sending the bitmap each
     * second overwhelms the RemoteViews bitmap handling and the art stops painting.
     */
    fun render(context: Context, snapshot: Snapshot, artwork: Bitmap?, palette: WidgetPalette) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, buildViews(context, snapshot, artwork, palette))
    }

    /**
     * Lightweight per-second update: only the progress bar + times, applied via
     * [AppWidgetManager.partiallyUpdateAppWidget] so the existing artwork and the
     * rest of the widget are left untouched (no bitmap re-send).
     */
    fun renderProgress(context: Context, snapshot: Snapshot, palette: WidgetPalette) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
        applyProgress(views, snapshot, palette)
        manager.partiallyUpdateAppWidget(ids, views)
    }

    private fun applyProgress(views: RemoteViews, snapshot: Snapshot, palette: WidgetPalette) {
        val duration = snapshot.durationSec
        val position = if (duration > 0) snapshot.positionSec.coerceAtMost(duration) else snapshot.positionSec
        val progress = if (duration > 0) ((position * 1000L) / duration).toInt() else 0
        views.setProgressBar(R.id.widget_progress, 1000, progress, false)
        views.setTextViewText(R.id.widget_position, formatTime(position))
        views.setTextViewText(R.id.widget_duration, if (duration > 0) formatTime(duration) else "--:--")
        views.setTextColor(R.id.widget_position, palette.textSecondary)
        views.setTextColor(R.id.widget_duration, palette.textSecondary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                R.id.widget_progress,
                "setProgressTintList",
                ColorStateList.valueOf(palette.accent),
            )
            views.setColorStateList(
                R.id.widget_progress,
                "setProgressBackgroundTintList",
                ColorStateList.valueOf(palette.textSecondary),
            )
        }
    }

    fun buildViews(
        context: Context,
        snapshot: Snapshot,
        artwork: Bitmap?,
        palette: WidgetPalette,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

        views.setTextViewText(R.id.widget_title, snapshot.title)
        views.setTextViewText(
            R.id.widget_artist,
            if (snapshot.isCasting) {
                snapshot.castDeviceName?.let { "Casting to $it" } ?: "Casting"
            } else {
                snapshot.artist
            },
        )

        // Progress bar + elapsed/total times (also used by the lightweight
        // partial updates each second).
        applyProgress(views, snapshot, palette)

        // Recolour to match the in-app theme. setColorFilter works on all API
        // levels; background tinting (rounded shapes) needs API 31+.
        views.setTextColor(R.id.widget_title, palette.textPrimary)
        views.setTextColor(R.id.widget_artist, palette.textSecondary)
        views.setInt(R.id.widget_previous, "setColorFilter", palette.textPrimary)
        views.setInt(R.id.widget_next, "setColorFilter", palette.textPrimary)
        views.setInt(R.id.widget_play_pause, "setColorFilter", palette.accent)
        views.setInt(R.id.widget_cast, "setColorFilter", if (snapshot.isCasting) palette.accent else palette.textSecondary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                R.id.widget_root,
                "setBackgroundTintList",
                ColorStateList.valueOf(palette.surface),
            )
            views.setColorStateList(
                R.id.widget_art,
                "setBackgroundTintList",
                ColorStateList.valueOf(palette.artBackground),
            )
        }

        if (artwork != null) {
            views.setImageViewBitmap(R.id.widget_art, artwork)
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_placeholder)
            // Tint only the placeholder glyph — never a real artwork bitmap.
            views.setInt(R.id.widget_art, "setColorFilter", palette.accent)
        }

        views.setImageViewResource(
            R.id.widget_play_pause,
            if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        // Tapping the body (or the cast icon) opens the app. A receiver picker
        // can't be shown from a widget — the real MediaRouteButton lives on Now
        // Playing — so the cast icon just opens the app there.
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setOnClickPendingIntent(R.id.widget_cast, openAppIntent(context))

        // Transport controls are only meaningful when something is loaded.
        val controlsVisible = if (snapshot.hasContent) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.widget_previous, controlsVisible)
        views.setViewVisibility(R.id.widget_play_pause, controlsVisible)
        views.setViewVisibility(R.id.widget_next, controlsVisible)

        if (snapshot.hasContent) {
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                command(context, NowPlayingWidgetProvider.ACTION_PLAY_PAUSE),
            )
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                command(context, NowPlayingWidgetProvider.ACTION_PREVIOUS),
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                command(context, NowPlayingWidgetProvider.ACTION_NEXT),
            )
        }
        return views
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatTime(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val minutes = s / 60
        val seconds = s % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun command(context: Context, action: String): PendingIntent {
        val intent = Intent(context, NowPlayingWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
