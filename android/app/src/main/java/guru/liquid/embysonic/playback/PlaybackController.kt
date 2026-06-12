package guru.liquid.embysonic.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
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

    // Secondary player used only to play the OUTGOING track's tail during a
    // crossfade, so the primary can advance to the next track early. Created
    // lazily; only ever active when crossfade is enabled for music.
    private val fadePlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
            .build()
            .also { secondary ->
                secondary.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        fadePlayerReady = playbackState == Player.STATE_READY
                        Log.d(TAG, "Crossfade helper state=$playbackState ready=$fadePlayerReady")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        fadePlayerReady = false
                        Log.w(TAG, "Crossfade helper failed", error)
                    }
                })
            }
    }

    @Volatile
    private var crossfadeInProgress = false
    @Volatile
    private var crossfadeArmed = false
    @Volatile
    private var fadePlayerReady = false
    private var crossfadeArmedIndex = -1
    private var crossfadeJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var queue: List<PlaybackTrack> = emptyList()
    private var queueShuffled: Boolean = false
    private val musicResumeClearedByStop = mutableSetOf<String>()
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

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (crossfadeInProgress) {
                    Log.d(TAG, "Crossfade primary state=$playbackState playing=${player.isPlaying}")
                }
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
        scope.launch {
            while (isActive) {
                maybeStartCrossfade()
                delay(CROSSFADE_POLL_MS)
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
        val tracks = items.map { item ->
            item.toPlaybackTrack().let { track ->
                if (track.id in musicResumeClearedByStop) {
                    track.copy(playbackPositionMs = 0L)
                } else {
                    track
                }
            }
        }
        if (tracks.isEmpty()) return
        cancelCrossfade()
        reportStopped(lastReportedState)
        lastReportedState = PlaybackUiState()
        if (playWhenReady) startService()
        playSessionId = UUID.randomUUID().toString()
        lastProgressReportMs = 0
        lastStartedItemId = null
        refreshHeaders()
        resetRepeatForNewSession()
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
        cancelCrossfade()
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
        cancelCrossfade()
        // Stopping (closing Now Playing / mini-bar) forgets a music track's
        // position so it starts fresh next time. Audiobooks still keep their
        // resume point — only pause-then-exit preserves resume for music.
        val stoppedTrack = lastReportedState.currentTrack
        val clearResume = stoppedTrack != null && !stoppedTrack.isLongForm
        if (clearResume) musicResumeClearedByStop += stoppedTrack.id
        reportStopped(lastReportedState, clearResume = clearResume)
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
        cancelCrossfade()
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
        cancelCrossfade()
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
        publishState()
    }

    fun skipNext() {
        cancelCrossfade()
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        publishState()
    }

    fun seekToQueueIndex(index: Int) {
        if (index !in queue.indices) return
        cancelCrossfade()
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
        cancelCrossfade()
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

    private fun resetRepeatForNewSession() {
        player.repeatMode = Player.REPEAT_MODE_OFF
        scope.launch { settings.setPlaybackRepeatMode(PlaybackRepeatMode.OFF.name) }
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

    /**
     * Two-phase crossfade driver, polled while playing. Phase 1 *arms* a few
     * seconds before the blend point by preloading the outgoing track's tail on
     * the secondary player (buffered while paused, so there's no gap when it
     * starts). Phase 2 *fires* at the blend point: it starts the buffered tail,
     * advances the primary to the next track, and ramps their volumes past each
     * other. Never runs for audiobooks (long-form), repeat-one, the last track,
     * or a next track that is long-form.
     */
    private fun maybeStartCrossfade() {
        if (crossfadeInProgress) return
        val snap = settings.snapshot()
        if (!snap.crossfadeEnabled) {
            cancelCrossfade()
            return
        }
        if (!player.isPlaying) return
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        if (crossfadeArmed && crossfadeArmedIndex != index) {
            cancelCrossfade()
        }
        val current = queue.getOrNull(index) ?: return
        if (current.isLongForm) return
        if ((streamOffsetsByIndex[index] ?: 0L) > 0L) return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val next = queue.getOrNull(nextIndex) ?: return
        if (next.isLongForm) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val crossfadeMs = snap.crossfadeDurationMs.toLong()
        val tailFromMs = (duration - crossfadeMs).coerceAtLeast(0)
        val remaining = duration - player.currentPosition.coerceAtLeast(0)

        if (!crossfadeArmed && remaining in 1..(crossfadeMs + CROSSFADE_PRELOAD_MS)) {
            armCrossfade(current, tailFromMs, index)
        }
        if (crossfadeArmed && crossfadeArmedIndex == index && remaining in 1..crossfadeMs) {
            val outgoingPositionMs = duration - remaining
            val helperHasTailBuffered = fadePlayerReady &&
                fadePlayer.bufferedPosition >= outgoingPositionMs + CROSSFADE_BUFFER_MARGIN_MS
            when {
                helperHasTailBuffered -> fireCrossfade(
                    blendDurationMs = remaining,
                )
                remaining <= MIN_CROSSFADE_START_MS -> {
                    Log.w(TAG, "Crossfade helper was not ready; preserving normal transition")
                    cancelCrossfade()
                }
            }
        }
    }

    private fun armCrossfade(outgoing: PlaybackTrack, tailFromMs: Long, index: Int) {
        crossfadeArmed = true
        crossfadeArmedIndex = index
        fadePlayerReady = false
        // Use the normal direct-play source and seek to the tail. Reopening the
        // track through Emby's transcoder is too slow for a reliable handoff.
        // Reset playWhenReady before replacing the item: stop() deliberately
        // retains it, which can otherwise consume the tail during preloading.
        fadePlayer.pause()
        fadePlayer.stop()
        fadePlayer.clearMediaItems()
        fadePlayer.volume = 0f
        fadePlayer.playWhenReady = false
        fadePlayer.setMediaItem(mediaItem(outgoing, 0L))
        fadePlayer.seekTo(tailFromMs)
        fadePlayer.prepare()
        Log.d(
            TAG,
            "Crossfade armed index=$index tailFromMs=$tailFromMs playWhenReady=${fadePlayer.playWhenReady}",
        )
    }

    private fun fireCrossfade(blendDurationMs: Long) {
        crossfadeInProgress = true
        crossfadeArmed = false
        // The helper is already paused at the blend point. Seeking it again here
        // discards its buffered decoder state and creates the very gap it exists
        // to cover.
        fadePlayer.volume = 1f
        fadePlayer.play()
        // Advance the primary now; the buffered helper keeps the outgoing track
        // audible while the next decoder becomes ready.
        player.volume = 0f
        player.seekToNextMediaItem()
        Log.d(TAG, "Crossfade fired durationMs=$blendDurationMs")
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            // Do not spend the fade duration while the incoming decoder is
            // buffering. The outgoing helper remains at full volume meanwhile.
            var readyWaitMs = 0L
            while (
                player.playbackState != Player.STATE_READY &&
                readyWaitMs < CROSSFADE_INCOMING_READY_TIMEOUT_MS
            ) {
                delay(CROSSFADE_READY_POLL_MS)
                readyWaitMs += CROSSFADE_READY_POLL_MS
            }
            if (player.playbackState != Player.STATE_READY) {
                Log.w(TAG, "Incoming track missed crossfade readiness timeout")
                endCrossfade()
                return@launch
            }
            Log.d(TAG, "Crossfade ramp starting after readyWaitMs=$readyWaitMs")
            val steps = (blendDurationMs / CROSSFADE_RAMP_STEP_MS).toInt().coerceAtLeast(1)
            for (step in 1..steps) {
                val f = step.toFloat() / steps
                // Bring the incoming track forward early enough to remain
                // perceptible beneath a loud outgoing track. The helper reset
                // in armCrossfade guarantees this floor begins only at the
                // configured blend point, never during preload.
                val incomingProgress = f.toDouble().pow(INCOMING_FADE_EXPONENT).toFloat()
                val incomingCurve = sin(incomingProgress * (PI.toFloat() / 2f))
                player.volume = INCOMING_START_VOLUME +
                    ((1f - INCOMING_START_VOLUME) * incomingCurve)
                fadePlayer.volume = cos(f * (PI.toFloat() / 2f))
                delay(CROSSFADE_RAMP_STEP_MS)
            }
            endCrossfade()
        }
    }

    private fun endCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        fadePlayer.stop()
        fadePlayer.clearMediaItems()
        player.volume = 1f
        crossfadeInProgress = false
        crossfadeArmed = false
        fadePlayerReady = false
        crossfadeArmedIndex = -1
    }

    /** Aborts any armed or in-flight crossfade and restores the primary's volume. */
    private fun cancelCrossfade() {
        if (!crossfadeInProgress && !crossfadeArmed) return
        endCrossfade()
    }

    private fun reportStarted(track: PlaybackTrack, positionMs: Long) {
        musicResumeClearedByStop -= track.id
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

    private fun reportStopped(state: PlaybackUiState, clearResume: Boolean = false) {
        val track = state.currentTrack ?: return
        val resumePositionMs = if (clearResume) 0L else state.positionMs
        scope.launch {
            runCatching {
                embyApi.reportPlaybackStopped(
                    PlaybackReportDto(
                        itemId = track.id,
                        positionTicks = resumePositionMs.msToTicks(),
                        playSessionId = playSessionId,
                        isPaused = true,
                        playlistIndex = state.currentIndex,
                        playlistLength = state.queue.size,
                    ),
                )
            }
            runCatching { syncResumePosition(track, resumePositionMs) }
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
        const val CROSSFADE_POLL_MS = 50L
        const val CROSSFADE_RAMP_STEP_MS = 50L
        const val CROSSFADE_READY_POLL_MS = 10L
        const val CROSSFADE_INCOMING_READY_TIMEOUT_MS = 1_500L
        const val CROSSFADE_PRELOAD_MS = 12_000L
        const val CROSSFADE_BUFFER_MARGIN_MS = 500L
        const val MIN_CROSSFADE_START_MS = 2_000L
        const val INCOMING_FADE_EXPONENT = 0.5
        const val INCOMING_START_VOLUME = 0.18f
        const val TAG = "PlaybackController"
    }
}
