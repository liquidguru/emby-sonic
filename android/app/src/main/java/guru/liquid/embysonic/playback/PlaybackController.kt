package guru.liquid.embysonic.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import kotlin.random.Random
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
    private var queueShuffled: Boolean = false

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
        setQueue(items = items, startItem = startItem, shuffled = false, playWhenReady = true)
    }

    fun prepareShuffledQueue(items: List<LibraryItem>) {
        val tracks = items.shuffled(Random(System.nanoTime()))
        prepareQueue(items = tracks, shuffled = true)
    }

    fun prepareQueue(items: List<LibraryItem>, shuffled: Boolean) {
        val startItem = items.firstOrNull() ?: return
        setQueue(items = items, startItem = startItem, shuffled = shuffled, playWhenReady = player.isPlaying)
    }

    private fun setQueue(
        items: List<LibraryItem>,
        startItem: LibraryItem,
        shuffled: Boolean,
        playWhenReady: Boolean,
    ) {
        val startIndex = items.indexOfFirst { it.id == startItem.id }.coerceAtLeast(0)
        val tracks = items.map { it.toPlaybackTrack() }
        if (tracks.isEmpty()) return
        if (playWhenReady) startService()
        refreshHeaders()
        queue = tracks
        queueShuffled = shuffled
        player.setMediaItems(tracks.map(::mediaItem), startIndex, C.TIME_UNSET)
        if (playWhenReady) {
            player.prepare()
            player.play()
        } else {
            player.pause()
        }
        publishState()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        publishState()
    }

    fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        queue = emptyList()
        queueShuffled = false
        context.stopService(Intent(context, SonicPlaybackService::class.java))
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

    fun seekToQueueIndex(index: Int) {
        if (index !in queue.indices) return
        player.seekTo(index, 0L)
        player.play()
        publishState()
    }

    fun shuffleQueue() {
        if (queue.size < 2) return
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val currentTrack = queue.getOrNull(currentIndex)
        val currentPosition = player.currentPosition.coerceAtLeast(0)
        val wasPlaying = player.isPlaying
        val shuffledTail = queue
            .filterNot { it.id == currentTrack?.id }
            .shuffled(Random(System.nanoTime()))
        queue = if (currentTrack != null) listOf(currentTrack) + shuffledTail else shuffledTail
        queueShuffled = true
        player.setMediaItems(queue.map(::mediaItem), 0, currentPosition)
        player.prepare()
        if (wasPlaying) player.play() else player.pause()
        publishState()
    }

    fun cycleRepeatMode() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
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
        val snap = settings.snapshot()
        val base = snap.serverUrl?.trimEnd('/')
            ?: throw IllegalStateException("No Emby server configured")
        val userId = snap.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Not signed in")
        return Uri.parse("$base/Audio/${Uri.encode(itemId)}/universal")
            .buildUpon()
            .appendQueryParameter("UserId", userId)
            .appendQueryParameter("MaxStreamingBitrate", "140000000")
            .appendQueryParameter("Container", "mp3,aac,m4a,flac,webma,webm,wav,ogg")
            .appendQueryParameter("AudioCodec", "mp3,aac,flac,vorbis,opus")
            .appendQueryParameter("TranscodingContainer", "mp3")
            .appendQueryParameter("TranscodingProtocol", "http")
            .build()
            .toString()
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
        context.startService(intent)
    }

    private fun publishState() {
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: queue.getOrNull(index)?.durationMs ?: 0
        _state.value = PlaybackUiState(
            currentTrack = queue.getOrNull(index),
            queue = queue,
            currentIndex = index,
            isPlaying = player.isPlaying,
            shuffleEnabled = queueShuffled,
            repeatMode = player.repeatMode.toPlaybackRepeatMode(),
            canSkipPrevious = player.hasPreviousMediaItem(),
            canSkipNext = player.hasNextMediaItem(),
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
            bufferedMs = player.bufferedPosition.coerceAtLeast(0),
        )
    }

    private fun Int.toPlaybackRepeatMode(): PlaybackRepeatMode = when (this) {
        Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
        Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
        else -> PlaybackRepeatMode.OFF
    }
}
