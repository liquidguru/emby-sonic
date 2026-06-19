package guru.liquid.embysonic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
        )
    }

    /** Push [snapshot] (and the already-loaded [artwork], if any) to every widget instance. */
    fun render(context: Context, snapshot: Snapshot, artwork: Bitmap?) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, buildViews(context, snapshot, artwork))
    }

    fun buildViews(context: Context, snapshot: Snapshot, artwork: Bitmap?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

        views.setTextViewText(R.id.widget_title, snapshot.title)
        views.setTextViewText(R.id.widget_artist, snapshot.artist)

        if (artwork != null) {
            views.setImageViewBitmap(R.id.widget_art, artwork)
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_placeholder)
        }

        views.setImageViewResource(
            R.id.widget_play_pause,
            if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        // Tapping anywhere on the body opens the app (Now Playing).
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))

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
