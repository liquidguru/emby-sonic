package guru.liquid.embysonic.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import guru.liquid.embysonic.BuildConfig
import guru.liquid.embysonic.data.emby.EmbyApi
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.dto.PlaybackReportDto
import guru.liquid.embysonic.data.emby.dto.UserDataUpdateDto
import guru.liquid.embysonic.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embyApi: EmbyApi,
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
    private var streamOffsetsByIndex: MutableMap<Int, Long> = mutableMapOf()
    private var playSessionId: String = UUID.randomUUID().toString()
    private var lastProgressReportMs: Long = 0
    private var lastReportedState: PlaybackUiState = PlaybackUiState()
    private var lastStartedItemId: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                publishState()
            }
        })
        scope.launch {
            settings.playbackRepeatMode
                .distinctUntilChanged()
                .collect { repeatMode ->
                    player.repeatMode = repeatMode.toPlayerRepeatMode()
                    publishState()
                }
        }
        scope.launch {
            while (isActive) {
                publishState()
                reportProgressIfDue()
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
        reportStopped(lastReportedState)
        lastReportedState = PlaybackUiState()
        if (playWhenReady) startService()
        playSessionId = UUID.randomUUID().toString()
        lastProgressReportMs = 0
        lastStartedItemId = null
        refreshHeaders()
        queue = tracks
        queueShuffled = shuffled
        streamOffsetsByIndex = tracks
            .mapIndexedNotNull { index, track ->
                track.streamStartOffset().takeIf { it > 0 }?.let { index to it }
            }
            .toMap()
            .toMutableMap()
        val resumePosition = tracks.getOrNull(startIndex)?.playerStartPosition(startIndex) ?: 0
        player.setMediaItems(tracks.mapIndexed { index, track -> mediaItem(track, streamOffsetsByIndex[index] ?: 0L) }, startIndex, resumePosition)
        if (playWhenReady) {
            player.prepare()
            player.play()
            reportStarted(tracks[startIndex], tracks[startIndex].absolutePosition(startIndex, resumePosition))
        } else {
            player.pause()
        }
        publishState()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
                player.prepare()
            }
            player.play()
        }
        publishState()
        reportProgress(
            state = lastReportedState,
            force = true,
            eventName = if (lastReportedState.isPlaying) "Unpause" else "Pause",
        )
    }

    fun stopPlayback() {
        reportStopped(lastReportedState)
        lastReportedState = PlaybackUiState()
        player.stop()
        player.clearMediaItems()
        queue = emptyList()
        queueShuffled = false
        streamOffsetsByIndex.clear()
        lastStartedItemId = null
        context.stopService(Intent(context, SonicPlaybackService::class.java))
        publishState()
    }

    fun seekTo(positionMs: Long) {
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val track = queue.getOrNull(index)
        if (track != null && track.isLongForm) {
            seekLongFormTo(index = index, track = track, positionMs = positionMs)
        } else {
            player.seekTo(positionMs.coerceAtLeast(0))
        }
        publishState()
        reportProgress(lastReportedState, force = true, eventName = "TimeUpdate")
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
        reportStopped(lastReportedState)
        lastReportedState = PlaybackUiState()
        streamOffsetsByIndex.remove(index)
        player.replaceMediaItem(index, mediaItem(queue[index], 0L))
        player.seekTo(index, 0L)
        player.play()
        reportStarted(queue[index], 0L)
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
        streamOffsetsByIndex = queue
            .mapIndexedNotNull { index, track ->
                track.streamStartOffset().takeIf { it > 0 }?.let { index to it }
            }
            .toMap()
            .toMutableMap()
        player.setMediaItems(queue.mapIndexed { index, track -> mediaItem(track, streamOffsetsByIndex[index] ?: 0L) }, 0, currentPosition)
        player.prepare()
        if (wasPlaying) player.play() else player.pause()
        publishState()
    }

    fun cycleRepeatMode() {
        val nextMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> PlaybackRepeatMode.ALL
            Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ONE
            else -> PlaybackRepeatMode.OFF
        }
        player.repeatMode = nextMode.toPlayerRepeatMode()
        scope.launch { settings.setPlaybackRepeatMode(nextMode.name) }
        publishState()
    }

    private fun mediaItem(track: PlaybackTrack, startOffsetMs: Long): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.imageUrl?.let(Uri::parse))
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(streamUrl(track.id, startOffsetMs))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun streamUrl(itemId: String, startOffsetMs: Long): String {
        val snap = settings.snapshot()
        val base = snap.serverUrl?.trimEnd('/')
            ?: throw IllegalStateException("No Emby server configured")
        val userId = snap.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Not signed in")
        val path = if (startOffsetMs > 0) "stream" else "universal"
        val builder = Uri.parse("$base/Audio/${Uri.encode(itemId)}/$path")
            .buildUpon()
            .appendQueryParameter("UserId", userId)
            .appendQueryParameter("MaxStreamingBitrate", "140000000")
            .appendQueryParameter("Container", if (startOffsetMs > 0) "mp3" else "mp3,aac,m4a,mp4,m4b,flac,webma,webm,wav,ogg")
            .appendQueryParameter("AudioCodec", if (startOffsetMs > 0) "mp3" else "mp3,aac,flac,vorbis,opus")
            .appendQueryParameter("TranscodingContainer", "mp3")
            .appendQueryParameter("TranscodingProtocol", "http")
            .appendQueryParameter("PlaySessionId", playSessionId)
        if (startOffsetMs > 0) {
            builder
                .appendQueryParameter("Static", "false")
                .appendQueryParameter("MediaSourceId", "mediasource_$itemId")
                .appendQueryParameter("StartTimeTicks", startOffsetMs.msToTicks().toString())
        }
        return builder.build().toString()
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
        val streamOffset = streamOffsetsByIndex[index] ?: 0L
        val position = streamOffset + player.currentPosition.coerceAtLeast(0)
        val originalDuration = queue.getOrNull(index)?.durationMs
        val duration = if (streamOffset > 0) {
            originalDuration ?: player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        } else {
            player.duration.takeIf { it != C.TIME_UNSET } ?: originalDuration ?: 0
        }
        val nextState = PlaybackUiState(
            currentTrack = queue.getOrNull(index),
            queue = queue,
            currentIndex = index,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            playbackError = player.playerError?.friendlyMessage(),
            shuffleEnabled = queueShuffled,
            repeatMode = player.repeatMode.toPlaybackRepeatMode(),
            canSkipPrevious = player.hasPreviousMediaItem(),
            canSkipNext = player.hasNextMediaItem(),
            positionMs = position,
            durationMs = duration.coerceAtLeast(0),
            bufferedMs = streamOffset + player.bufferedPosition.coerceAtLeast(0),
        )
        val previous = lastReportedState
        if (previous.currentTrack?.id != null && previous.currentTrack.id != nextState.currentTrack?.id) {
            reportStopped(previous)
        }
        if (nextState.currentTrack != null && nextState.currentTrack.id != lastStartedItemId && nextState.isPlaying) {
            reportStarted(nextState.currentTrack, nextState.positionMs)
        }
        lastReportedState = nextState
        _state.value = nextState
    }

    private fun Int.toPlaybackRepeatMode(): PlaybackRepeatMode = when (this) {
        Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
        Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
        else -> PlaybackRepeatMode.OFF
    }

    private fun PlaybackRepeatMode.toPlayerRepeatMode(): Int = when (this) {
        PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
        PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
    }

    private fun String.toPlayerRepeatMode(): Int = when (uppercase()) {
        PlaybackRepeatMode.ALL.name -> Player.REPEAT_MODE_ALL
        PlaybackRepeatMode.ONE.name -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    private fun PlaybackTrack.resumePositionForPlayback(): Long {
        val resume = playbackPositionMs.coerceAtLeast(0)
        val duration = durationMs
        if (duration != null && resume !in 1 until (duration - RESUME_END_PADDING_MS)) return 0
        val preRoll = if ((duration ?: 0) >= LONG_FORM_MIN_DURATION_MS) {
            LONG_FORM_RESUME_PREROLL_MS
        } else {
            0L
        }
        return (resume - preRoll).coerceAtLeast(0)
    }

    private val PlaybackTrack.isLongForm: Boolean
        get() = (durationMs ?: 0L) >= LONG_FORM_MIN_DURATION_MS

    private fun PlaybackTrack.streamStartOffset(): Long =
        if (isLongForm) resumePositionForPlayback() else 0L

    private fun PlaybackTrack.playerStartPosition(index: Int): Long =
        resumePositionForPlayback() - (streamOffsetsByIndex[index] ?: 0L)

    private fun PlaybackTrack.absolutePosition(index: Int, playerPositionMs: Long): Long =
        (streamOffsetsByIndex[index] ?: 0L) + playerPositionMs

    private fun seekLongFormTo(index: Int, track: PlaybackTrack, positionMs: Long) {
        val nextOffset = positionMs.coerceAtLeast(0)
        val wasPlaying = player.isPlaying
        streamOffsetsByIndex[index] = nextOffset
        player.replaceMediaItem(index, mediaItem(track, nextOffset))
        player.seekTo(index, 0L)
        player.prepare()
        if (wasPlaying) player.play() else player.pause()
    }

    private fun reportStarted(track: PlaybackTrack, positionMs: Long) {
        lastStartedItemId = track.id
        scope.launch {
            runCatching {
                embyApi.reportPlaybackStarted(
                    PlaybackReportDto(
                        itemId = track.id,
                        positionTicks = positionMs.msToTicks(),
                        playSessionId = playSessionId,
                        isPaused = false,
                    ),
                )
            }
        }
    }

    private fun reportProgressIfDue() {
        val state = lastReportedState
        if (state.currentTrack == null) return
        if (state.playbackError != null) return
        reportProgress(state, force = false)
    }

    private fun reportProgress(
        state: PlaybackUiState,
        force: Boolean,
        eventName: String = "TimeUpdate",
    ) {
        val track = state.currentTrack ?: return
        if (state.playbackError != null) return
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressReportMs < PROGRESS_REPORT_INTERVAL_MS) return
        lastProgressReportMs = now
        scope.launch {
            runCatching {
                embyApi.reportPlaybackProgress(
                    PlaybackReportDto(
                        itemId = track.id,
                        positionTicks = state.positionMs.msToTicks(),
                        playSessionId = playSessionId,
                        isPaused = !state.isPlaying,
                        playlistIndex = state.currentIndex,
                        playlistLength = state.queue.size,
                        eventName = eventName,
                    ),
                )
            }
            runCatching { syncResumePosition(track, state.positionMs) }
        }
    }

    private fun reportStopped(state: PlaybackUiState) {
        val track = state.currentTrack ?: return
        scope.launch {
            runCatching {
                embyApi.reportPlaybackStopped(
                    PlaybackReportDto(
                        itemId = track.id,
                        positionTicks = state.positionMs.msToTicks(),
                        playSessionId = playSessionId,
                        isPaused = true,
                        playlistIndex = state.currentIndex,
                        playlistLength = state.queue.size,
                    ),
                )
            }
            runCatching { syncResumePosition(track, state.positionMs) }
        }
    }

    private suspend fun syncResumePosition(track: PlaybackTrack, positionMs: Long) {
        val userId = settings.snapshot().userId?.takeIf { it.isNotBlank() } ?: return
        val position = durableResumePosition(track, positionMs)
        embyApi.updateUserData(
            userId = userId,
            itemId = track.id,
            body = UserDataUpdateDto(
                playbackPositionTicks = position.msToTicks(),
                played = false,
            ),
        )
    }

    private fun durableResumePosition(track: PlaybackTrack, positionMs: Long): Long {
        val position = positionMs.coerceAtLeast(0)
        if (position < RESUME_MIN_POSITION_MS) return 0
        val duration = track.durationMs ?: return position
        return if (position < duration - RESUME_END_PADDING_MS) position else 0
    }

    private fun Long.msToTicks(): Long = coerceAtLeast(0) * 10_000

    private fun PlaybackException.friendlyMessage(): String = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't open this stream"
        else -> "Playback failed"
    }

    private companion object {
        const val PROGRESS_REPORT_INTERVAL_MS = 3_000L
        const val RESUME_MIN_POSITION_MS = 5_000L
        const val RESUME_END_PADDING_MS = 5_000L
        const val LONG_FORM_MIN_DURATION_MS = 20 * 60 * 1000L
        const val LONG_FORM_RESUME_PREROLL_MS = 5_000L
    }
}
