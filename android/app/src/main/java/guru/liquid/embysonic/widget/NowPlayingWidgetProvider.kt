package guru.liquid.embysonic.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import guru.liquid.embysonic.playback.PlaybackController

/**
 * Home-screen mini-player widget. Display is pushed by [PlaybackController]
 * whenever playback state changes; this provider handles the system update
 * callback and the transport-button broadcasts.
 */
class NowPlayingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // A freshly added widget needs an immediate render; ask the controller to
        // repaint with whatever it currently knows (and its cached artwork).
        controller(context).refreshWidget()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE -> controller(context).togglePlayPause()
            ACTION_PREVIOUS -> controller(context).skipPrevious()
            ACTION_NEXT -> controller(context).skipNext()
        }
    }

    private fun controller(context: Context): PlaybackController =
        EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetPlaybackEntryPoint::class.java)
            .playbackController()

    companion object {
        const val ACTION_PLAY_PAUSE = "guru.liquid.embysonic.widget.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "guru.liquid.embysonic.widget.PREVIOUS"
        const val ACTION_NEXT = "guru.liquid.embysonic.widget.NEXT"
    }
}
