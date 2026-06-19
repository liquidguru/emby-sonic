package guru.liquid.embysonic.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import guru.liquid.embysonic.playback.PlaybackController

/**
 * Lets the broadcast-receiver-based widget reach the singleton
 * [PlaybackController] from the application graph, so widget taps drive the
 * exact same controller as the in-app UI and media notification.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetPlaybackEntryPoint {
    fun playbackController(): PlaybackController
}
