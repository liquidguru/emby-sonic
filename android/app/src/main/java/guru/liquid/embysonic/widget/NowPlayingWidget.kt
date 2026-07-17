package guru.liquid.embysonic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
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

    /** Full widget update, including the artwork (a FileProvider URI). */
    fun render(context: Context, snapshot: Snapshot, artUri: Uri?, palettes: WidgetPalettes) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java))
        if (ids.isEmpty()) return
        if (artUri != null) grantArtToHost(context, artUri)
        manager.updateAppWidget(ids, buildViews(context, snapshot, artUri, palettes))
    }

    /**
     * Set a colour that follows the system appearance. On API 31+ both variants go
     * to the host, which picks per configuration and re-picks when it changes — so
     * the widget tracks light/dark without us repainting (see [WidgetPalettes]).
     * Below that, everything is dark anyway, so paint the single value.
     */
    private fun RemoteViews.setThemedColor(
        viewId: Int,
        methodName: String,
        palettes: WidgetPalettes,
        select: (WidgetPalette) -> Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setColorInt(viewId, methodName, select(palettes.day), select(palettes.night))
        } else {
            setInt(viewId, methodName, select(palettes.fallback))
        }
    }

    /** As [setThemedColor], for the tint-list setters (API 31+ only, as before). */
    private fun RemoteViews.setThemedTint(
        viewId: Int,
        methodName: String,
        palettes: WidgetPalettes,
        select: (WidgetPalette) -> Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        setColorStateList(
            viewId,
            methodName,
            ColorStateList.valueOf(select(palettes.day)),
            ColorStateList.valueOf(select(palettes.night)),
        )
    }

    /**
     * The widget host (launcher) is a different process, so it needs read access to
     * the art URI or the RemoteViews fails to apply ("can't load widget"). Grant the
     * default home/launcher package; the grant persists until reboot/revoke.
     */
    private fun grantArtToHost(context: Context, uri: Uri) {
        runCatching {
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
            if (home != null) context.grantUriPermission(home, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun applyProgress(views: RemoteViews, snapshot: Snapshot, palettes: WidgetPalettes) {
        val duration = snapshot.durationSec
        val position = if (duration > 0) snapshot.positionSec.coerceAtMost(duration) else snapshot.positionSec
        val progress = if (duration > 0) ((position * 1000L) / duration).toInt() else 0
        views.setProgressBar(R.id.widget_progress, 1000, progress, false)
        views.setTextViewText(R.id.widget_position, formatTime(position))
        views.setTextViewText(R.id.widget_duration, if (duration > 0) formatTime(duration) else "--:--")
        views.setThemedColor(R.id.widget_position, "setTextColor", palettes) { it.textSecondary }
        views.setThemedColor(R.id.widget_duration, "setTextColor", palettes) { it.textSecondary }
        views.setThemedTint(R.id.widget_progress, "setProgressTintList", palettes) { it.accent }
        views.setThemedTint(R.id.widget_progress, "setProgressBackgroundTintList", palettes) { it.textSecondary }
    }

    fun buildViews(
        context: Context,
        snapshot: Snapshot,
        artUri: Uri?,
        palettes: WidgetPalettes,
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

        // Progress bar + elapsed/total times.
        applyProgress(views, snapshot, palettes)

        // Recolour to match the in-app theme. Text/filter colours work on all API
        // levels; background tinting (rounded shapes) needs API 31+.
        views.setThemedColor(R.id.widget_title, "setTextColor", palettes) { it.textPrimary }
        views.setThemedColor(R.id.widget_artist, "setTextColor", palettes) { it.textSecondary }
        views.setThemedColor(R.id.widget_previous, "setColorFilter", palettes) { it.textPrimary }
        views.setThemedColor(R.id.widget_next, "setColorFilter", palettes) { it.textPrimary }
        views.setThemedColor(R.id.widget_play_pause, "setColorFilter", palettes) { it.accent }
        views.setThemedColor(R.id.widget_cast, "setColorFilter", palettes) {
            if (snapshot.isCasting) it.accent else it.textSecondary
        }
        views.setThemedTint(R.id.widget_root, "setBackgroundTintList", palettes) { it.surface }
        views.setThemedTint(R.id.widget_art_frame, "setBackgroundTintList", palettes) { it.artBackground }

        if (artUri != null) {
            // A FileProvider URI the launcher loads from disk; replayable across
            // re-inflation and not subject to the RemoteViews bitmap cache.
            views.setViewVisibility(R.id.widget_art, View.VISIBLE)
            views.setViewVisibility(R.id.widget_art_placeholder, View.GONE)
            views.setImageViewUri(R.id.widget_art, artUri)
        } else {
            views.setViewVisibility(R.id.widget_art, View.GONE)
            views.setViewVisibility(R.id.widget_art_placeholder, View.VISIBLE)
            views.setImageViewResource(R.id.widget_art_placeholder, R.drawable.ic_widget_placeholder)
            // Tint only the placeholder glyph — never real artwork.
            views.setThemedColor(R.id.widget_art_placeholder, "setColorFilter", palettes) { it.accent }
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
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        // Audiobooks run for hours: show H:MM:SS once past an hour, else M:SS.
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
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
