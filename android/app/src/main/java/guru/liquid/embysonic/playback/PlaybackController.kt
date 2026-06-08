package guru.liquid.embysonic.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import guru.liquid.embysonic.BuildConfig
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("liquidWave/${BuildConfig.VERSION_NAME}")

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var queue: List<PlaybackTrack> = emptyList()

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                publishState()
            }
        })
        scope.launch {
            while (isActive) {
                publishState()
                delay(500)
            }
        }
    }

    fun playQueue(items: List<LibraryItem>, startItem: LibraryItem) {
        val startIndex = items.indexOfFirst { it.id == startItem.id }.coerceAtLeast(0)
        val tracks = items.map { it.toPlaybackTrack() }
        if (tracks.isEmpty()) return
        startService()
        refreshHeaders()
        queue = tracks
        player.setMediaItems(tracks.map(::mediaItem), startIndex, C.TIME_UNSET)
        player.prepare()
        player.play()
        publishState()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        publishState()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
        publishState()
    }

    fun skipPrevious() {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
        publishState()
    }

    fun skipNext() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        publishState()
    }

    private fun mediaItem(track: PlaybackTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.imageUrl?.let(Uri::parse))
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(streamUrl(track.id))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun streamUrl(itemId: String): String {
        val base = settings.snapshot().serverUrl?.trimEnd('/')
            ?: throw IllegalStateException("No Emby server configured")
        return "$base/Items/${Uri.encode(itemId)}/Download"
    }

    private fun refreshHeaders() {
        val snap = settings.snapshot()
        val headers = linkedMapOf(
            "X-Emby-Authorization" to buildString {
                append("MediaBrowser ")
                append("Client=\"liquidWave\", ")
                append("Device=\"${Build.MODEL}\", ")
                append("DeviceId=\"${snap.deviceId}\", ")
                append("Version=\"${BuildConfig.VERSION_NAME}\"")
            },
        )
        snap.accessToken?.takeIf { it.isNotBlank() }?.let { headers["X-Emby-Token"] = it }
        httpDataSourceFactory.setDefaultRequestProperties(headers)
    }

    private fun startService() {
        val intent = Intent(context, SonicPlaybackService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun publishState() {
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: queue.getOrNull(index)?.durationMs ?: 0
        _state.value = PlaybackUiState(
            currentTrack = queue.getOrNull(index),
            queue = queue,
            currentIndex = index,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
            bufferedMs = player.bufferedPosition.coerceAtLeast(0),
        )
    }
}
