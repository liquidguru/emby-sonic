package guru.liquid.embysonic.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata as PlatformMediaMetadata
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import guru.liquid.embysonic.BuildConfig
import guru.liquid.embysonic.MainActivity
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.LoudnessRequestDto
import guru.liquid.embysonic.data.coordinator.dto.TrackEdgesDto
import guru.liquid.embysonic.data.coordinator.dto.QueueInjectRequestDto
import guru.liquid.embysonic.data.coordinator.toLibraryItem
import guru.liquid.embysonic.data.download.DownloadProgressStore
import guru.liquid.embysonic.data.download.DownloadStore
import guru.liquid.embysonic.data.emby.ContentKind
import guru.liquid.embysonic.data.emby.EmbyApi
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.dto.PlaybackReportDto
import guru.liquid.embysonic.data.emby.dto.UserDataUpdateDto
import guru.liquid.embysonic.data.recent.RecentPlay
import guru.liquid.embysonic.data.recent.RecentPlaysRepository
import guru.liquid.embysonic.data.session.PersistedSession
import guru.liquid.embysonic.data.session.PersistedTrack
import guru.liquid.embysonic.data.session.PlaybackSessionStore
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.data.settings.ThemeChoice
import guru.liquid.embysonic.widget.NowPlayingWidget
import guru.liquid.embysonic.widget.WidgetTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embyApi: EmbyApi,
    private val settings: SettingsRepository,
    private val audioEffects: AudioEffectsController,
    private val recentPlays: RecentPlaysRepository,
    private val prefetchCache: OfflinePrefetchCache,
    private val coordinator: CoordinatorApi,
    private val library: LibraryRepository,
    private val sessionStore: PlaybackSessionStore,
    private val downloadStore: DownloadStore,
    private val downloadProgress: DownloadProgressStore,
) {
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("liquidWave/${BuildConfig.VERSION_NAME}")
    private val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    private val mediaAudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // Both players share one audio session so a single Equalizer instance covers
    // normal playback AND the crossfade helper's tail.
    private val sharedAudioSessionId: Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()

    // Per-track volume normalisation. Each player gets its own gain processor in
    // its audio sink so the primary (incoming track) and the crossfade helper
    // (outgoing track) can be normalised to their own levels independently.
    private val mainGain = GainAudioProcessor()
    private val fadeGain = GainAudioProcessor()

    /** A renderers factory whose audio sink runs [gain] in its processing chain. */
    private fun audioRenderersFactory(gain: GainAudioProcessor): RenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(gain))
                    .build()
        }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(audioRenderersFactory(mainGain))
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
        .setAudioAttributes(mediaAudioAttributes, /* handleAudioFocus= */ true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .also { it.audioSessionId = sharedAudioSessionId }

    // Secondary player used only to play the OUTGOING track's tail during a
    // crossfade, so the primary can advance to the next track early. Created
    // on demand for each crossfade and fully RELEASED when the blend ends, so
    // normal single-track playback never holds a second decoder (which can
    // exhaust codec resources on constrained devices). It must NOT handle audio
    // focus — both players are meant to sound at once during a blend, and a
    // focus-handling helper would silence the primary.
    private var fadePlayer: ExoPlayer? = null

    /** The crossfade helper, created (with its listener) if it doesn't exist yet. */
    private fun fadePlayerInstance(): ExoPlayer =
        fadePlayer ?: ExoPlayer.Builder(context)
            .setRenderersFactory(audioRenderersFactory(fadeGain))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(mediaAudioAttributes, /* handleAudioFocus= */ false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { it.audioSessionId = sharedAudioSessionId }
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
                fadePlayer = secondary
            }

    /** Stop and fully release the crossfade helper so it holds no decoder. */
    private fun releaseFadePlayer() {
        fadePlayer?.release()
        fadePlayer = null
        fadePlayerReady = false
    }

    @Volatile
    private var crossfadeInProgress = false
    @Volatile
    private var crossfadeArmed = false
    @Volatile
    private var fadePlayerReady = false
    private var crossfadeArmedIndex = -1
    private var crossfadeTargetIndex = -1
    private var crossfadeJob: Job? = null
    // Precisely-timed fire scheduled once the helper's tail is buffered; non-null
    // means a fire is pending (or ran) for the armed index.
    private var crossfadeFireJob: Job? = null

    // The outgoing track + blend length while a crossfade is firing, published in
    // state so Now Playing can dissolve the artwork in sync with the audio.
    @Volatile
    private var crossfadeFromTrack: PlaybackTrack? = null
    private var crossfadeFromIndex: Int = -1
    @Volatile
    private var crossfadeBlendMs: Long = 0

    // Index whose crossfade is suppressed because the user seeked into its tail.
    private var suppressCrossfadeIndex = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private val _activePlayer = MutableStateFlow<Player>(player)
    val activePlayer: StateFlow<Player> = _activePlayer.asStateFlow()
    private var activePlayerRef: Player = player
    private var castPlayer: CastPlayer? = null

    // Gates the two ticking loops below so they only run while audio is actually
    // playing. Idle, they did 20 main-thread wakeups/second forever (the 50ms
    // crossfade poll) — wasting battery and, because the main looper was never
    // quiet, blocking on-device uiautomator from ever reaching idle. Discrete
    // state changes still publish via the onEvents listener; only the continuous
    // position tick and blend poll pause when not playing. Updated from onEvents.
    private val playbackActive = MutableStateFlow(false)

    private var queue: List<PlaybackTrack> = emptyList()
    private var queueShuffled: Boolean = false
    // Restores the last session (queue + position) after process death so the
    // widget/app can resume. Joined before acting on a cold widget command.
    private var restoreJob: Job? = null
    private var lastSessionPersistMs: Long = 0L
    private var streamOffsetsByIndex: MutableMap<Int, Long> = mutableMapOf()
    private var playSessionIdsByIndex: MutableMap<Int, String> = mutableMapOf()
    private var lastProgressReportMs: Long = 0
    private var lastReportedState: PlaybackUiState = PlaybackUiState()
    private var lastStartedItemId: String? = null
    private var prefetchJob: Job? = null
    private var prefetchSignature: String? = null
    private var offlinePrefetch = OfflinePrefetchState()

    // Volume normalisation: measured loudness (LUFS) per track id for the current
    // queue, fetched from the coordinator. Missing id = no data = unity gain.
    private val lufsByTrackId = mutableMapOf<String, Float>()
    @Volatile
    private var normalizationEnabled = true
    private var lastNormalizedTrackId: String? = null

    // Crossfade edge trimming (#38): where each track's audible music actually
    // starts/ends, fetched alongside loudness. Missing id = no data = blend
    // against the full duration (previous behaviour).
    private val edgesByTrackId = mutableMapOf<String, TrackEdgesDto>()
    @Volatile
    private var trimEdgesEnabled = true

    // How many upcoming tracks to pre-cache; driven by the user setting (collected
    // in init). The prefetch cache keeps this many (plus headroom) so a larger
    // value isn't immediately self-evicted.
    private var prefetchAheadCount = PREFETCH_AHEAD_COUNT
    private var sleepTimerJob: Job? = null
    private var sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF
    private var sleepTimerEndsAtMs: Long = 0L
    private var sleepTimerFiring = false
    private var audiobookSpeed: Float = settings.snapshot().audiobookSpeed
    private var guestDjEnabled = false
    private var guestDjLoading = false
    private var guestDjAttemptSignature: String? = null
    private var lastCastIndex: Int = C.INDEX_UNSET
    private var lastCastPositionMs: Long = C.TIME_UNSET
    private var castVolume = CastVolumeState()
    private var castVolumeController: ((Float) -> Unit)? = null
    private var castVolumeJob: Job? = null
    private var castVolumePendingTarget: Float? = null
    private var castVolumePendingUntilMs: Long = 0L

    // A MediaController connected to our own MediaSessionService. The UI drives
    // the shared ExoPlayer directly, so without this nothing connects to the
    // session — and Media3 only starts the foreground media notification (and
    // the foreground service that keeps playback alive in the background) once
    // a controller connects. This controller issues no commands; its presence
    // is what activates the notification lifecycle.
    private var notificationControllerFuture: ListenableFuture<MediaController>? = null
    private var notificationController: MediaController? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                publishFromPlayer(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (crossfadeInProgress) {
                    Log.d(TAG, "Crossfade primary state=$playbackState playing=${player.isPlaying}")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                recoverFromSourceError(error)
            }

            // Pauses can arrive without going through togglePlayPause — media
            // notification, Bluetooth controls, audio-focus loss, becoming-noisy.
            // Any of them must abort a pending or in-flight crossfade, or the
            // helper keeps playing the outgoing tail over a paused primary.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) cancelCrossfade()
                if (!playWhenReady && !sleepTimerFiring && sleepTimerMode != SleepTimerMode.OFF) {
                    clearSleepTimer()
                    publishState()
                }
            }

            // Seeks from the notification/MediaSession bypass seekTo()/skipNext().
            // Ignore the seek that fireCrossfade itself issues (it lands on
            // crossfadeTargetIndex); cancel on anything else.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (crossfadeArmed) {
                    cancelCrossfade()
                } else if (crossfadeInProgress && newPosition.mediaItemIndex != crossfadeTargetIndex) {
                    cancelCrossfade()
                }
            }
        })
        // Push any positions saved while offline up to Emby on startup (best-effort).
        flushPendingProgress()
        scope.launch {
            settings.playbackRepeatMode
                .distinctUntilChanged()
                .collect { repeatMode ->
                    player.repeatMode = repeatMode.toPlayerRepeatMode()
                    castPlayer?.repeatMode = repeatMode.toPlayerRepeatMode()
                    if (repeatMode.uppercase() != PlaybackRepeatMode.OFF.name && guestDjEnabled) {
                        guestDjEnabled = false
                        guestDjLoading = false
                        guestDjAttemptSignature = null
                    }
                    publishState()
                }
        }
        scope.launch {
            settings.audiobookSpeed
                .distinctUntilChanged()
                .collect { speed ->
                    audiobookSpeed = speed
                    applyPlaybackSpeed()
                    publishState()
                }
        }
        scope.launch {
            settings.crossfadeTrimEdges
                .distinctUntilChanged()
                .collect { enabled ->
                    trimEdgesEnabled = enabled
                    // Any armed blend was planned against the old anchor; drop it
                    // so the next one is planned with the new setting.
                    cancelCrossfade()
                }
        }
        scope.launch {
            settings.volumeNormalizationEnabled
                .distinctUntilChanged()
                .collect { enabled ->
                    normalizationEnabled = enabled
                    // Re-apply for the current track + any armed crossfade tail.
                    lastReportedState.currentTrack?.let { mainGain.gain = normalizationGainFor(it.id) }
                    crossfadeFromTrack?.let { fadeGain.gain = normalizationGainFor(it.id) }
                }
        }
        scope.launch {
            settings.prefetchAheadCount
                .distinctUntilChanged()
                .collect { count ->
                    prefetchAheadCount = count
                    // Keep a little headroom so the just-prefetched set isn't evicted.
                    prefetchCache.maxTracks = count + 2
                    schedulePrefetch(activePlayerRef.currentMediaItemIndex.coerceAtLeast(0))
                }
        }
        // collectLatest cancels the inner loop the moment playback stops and
        // relaunches it when it resumes, so neither loop spins while idle.
        scope.launch {
            playbackActive.collectLatest { active ->
                if (!active) return@collectLatest
                while (isActive) {
                    publishState()
                    reportProgressIfDue()
                    persistSession(throttle = true)
                    maybeFireEndOfTrackSleepTimer()
                    maybeInjectGuestDj()
                    delay(500)
                }
            }
        }
        scope.launch {
            playbackActive.collectLatest { active ->
                if (!active) return@collectLatest
                if (isCasting) return@collectLatest
                while (isActive) {
                    maybeStartCrossfade()
                    delay(CROSSFADE_POLL_MS)
                }
            }
        }
        // Bind the equalizer to the players' shared session.
        audioEffects.attach(sharedAudioSessionId)
        startWidgetUpdates()
        // Restore the last session on cold start so the widget/app can resume
        // after the process was killed. Paused, no autoplay, no network until the
        // user actually hits play.
        restoreJob = scope.launch { runCatching { restoreSession() } }
    }

    fun attachCastPlayer(player: CastPlayer) {
        if (castPlayer === player) return
        castPlayer = player
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                publishFromPlayer(player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && !sleepTimerFiring && sleepTimerMode != SleepTimerMode.OFF) {
                    clearSleepTimer()
                    publishState()
                }
            }
        })
    }

    fun setCastVolumeController(controller: ((Float) -> Unit)?) {
        castVolumeController = controller
    }

    fun setCastVolume(volume: Float) {
        if (!castVolume.available) return
        val normalized = volume.coerceIn(0f, 1f)
        castVolume = castVolume.copy(volume = normalized, pending = true)
        castVolumePendingTarget = normalized
        castVolumePendingUntilMs = SystemClock.elapsedRealtime() + CAST_VOLUME_PENDING_GRACE_MS
        publishCastVolumeState()
        castVolumeJob?.cancel()
        castVolumeJob = scope.launch {
            delay(CAST_VOLUME_DEBOUNCE_MS)
            castVolumeController?.invoke(normalized)
            delay(CAST_VOLUME_PENDING_GRACE_MS)
            if (castVolumePendingTarget == normalized) {
                castVolumePendingTarget = null
                castVolumePendingUntilMs = 0L
                castVolume = castVolume.copy(pending = false)
                publishCastVolumeState()
            }
        }
    }

    fun onCastVolumeChanged(volume: Double?, deviceName: String?) {
        val normalized = volume?.toFloat()?.coerceIn(0f, 1f) ?: return
        val pendingTarget = castVolumePendingTarget
        if (
            pendingTarget != null &&
            SystemClock.elapsedRealtime() < castVolumePendingUntilMs &&
            abs(normalized - pendingTarget) > CAST_VOLUME_RECONCILE_TOLERANCE
        ) {
            castVolume = castVolume.copy(
                deviceName = deviceName ?: castVolume.deviceName,
                pending = true,
            )
            publishCastVolumeState()
            return
        }
        castVolumePendingTarget = null
        castVolumePendingUntilMs = 0L
        castVolume = CastVolumeState(
            available = true,
            volume = normalized,
            deviceName = deviceName,
            pending = false,
        )
        publishCastVolumeState()
    }

    fun onCastVolumeSetFailed() {
        if (!castVolume.available) return
        castVolumePendingTarget = null
        castVolumePendingUntilMs = 0L
        castVolume = castVolume.copy(pending = false)
        publishCastVolumeState()
    }

    fun onCastVolumeUnavailable() {
        castVolumeJob?.cancel()
        castVolumeJob = null
        castVolumePendingTarget = null
        castVolumePendingUntilMs = 0L
        if (!castVolume.available) return
        castVolume = CastVolumeState()
        publishCastVolumeState()
    }

    private fun publishCastVolumeState() {
        _state.value = _state.value.copy(
            isCasting = isCasting,
            castVolume = castVolume.takeIf { isCasting } ?: CastVolumeState(),
        )
    }

    fun activePlayerSnapshot(): Player = activePlayerRef

    fun onCastSessionAvailable() {
        val remote = castPlayer ?: return
        if (activePlayerRef === remote) return
        val startIndex = player.currentMediaItemIndex.takeIf { it in queue.indices } ?: return
        val current = queue.getOrNull(startIndex) ?: return
        if (current.isLongForm) {
            Log.i(TAG, "Audiobook casting is not supported yet; keeping local playback active")
            return
        }
        val handoffPosition = currentSessionPositionMs()?.coerceAtLeast(0L)
            ?: player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.isPlaying || player.playWhenReady
        cancelCrossfade()
        prefetchJob?.cancel()
        prefetchSignature = null
        setOfflinePrefetchState(OfflinePrefetchState(OfflinePrefetchStatus.UNAVAILABLE))
        audioEffects.setSuppressedForRemotePlayback(true)
        player.pause()
        remote.repeatMode = player.repeatMode
        setActivePlayer(remote)
        Log.i(
            TAG,
            "Cast handoff local->remote queue=${queue.size} index=$startIndex positionMs=$handoffPosition wasPlaying=$wasPlaying",
        )
        remote.setMediaItems(castMediaItems(), startIndex, handoffPosition)
        remote.prepare()
        if (wasPlaying) playActive() else remote.pause()
        publishState()
    }

    fun onCastSessionUnavailable(resumePlayback: Boolean = true) {
        val remote = castPlayer ?: return
        if (activePlayerRef !== remote) return
        if (queue.isEmpty()) {
            setActivePlayer(player)
            audioEffects.setSuppressedForRemotePlayback(false)
            lastCastIndex = C.INDEX_UNSET
            lastCastPositionMs = C.TIME_UNSET
            publishState()
            return
        }
        val startIndex = remote.currentMediaItemIndex.takeIf { it in queue.indices }
            ?: state.value.currentIndex.takeIf { it in queue.indices }
            ?: lastCastIndex.takeIf { it in queue.indices }
            ?: 0
        val remotePosition = remote.currentPosition.coerceAtLeast(0L)
        val statePosition = state.value
            .takeIf { it.currentIndex == startIndex && it.positionMs > 0L }
            ?.positionMs
        val snapshotPosition = lastCastPositionMs
            .takeIf { lastCastIndex == startIndex && it != C.TIME_UNSET && it > 0L }
        val handoffPosition = maxOf(remotePosition, statePosition ?: 0L, snapshotPosition ?: 0L)
        val wasPlaying = remote.isPlaying || remote.playWhenReady
        val repeatMode = remote.repeatMode
        setActivePlayer(player)
        audioEffects.setSuppressedForRemotePlayback(false)
        player.volume = 1f
        player.repeatMode = repeatMode
        Log.i(
            TAG,
            "Cast handoff remote->local queue=${queue.size} index=$startIndex positionMs=$handoffPosition wasPlaying=$wasPlaying",
        )
        player.setMediaItems(localMediaItems(), startIndex, localPlayerPosition(startIndex, handoffPosition))
        player.prepare()
        // Only auto-resume on the phone for a clean, user-initiated stop. On a
        // network drop the receiver keeps playing autonomously, so auto-playing
        // here would double the audio (phone + receiver). Hand back paused instead.
        if (resumePlayback && wasPlaying && queue.isNotEmpty()) playActive() else player.pause()
        lastCastIndex = C.INDEX_UNSET
        lastCastPositionMs = C.TIME_UNSET
        schedulePrefetch(startIndex)
        publishState()
    }

    private fun publishFromPlayer(eventPlayer: Player) {
        if (eventPlayer !== activePlayerRef) return
        playbackActive.value = eventPlayer.isPlaying
        publishState()
    }

    private fun setActivePlayer(next: Player) {
        if (activePlayerRef === next) return
        activePlayerRef = next
        _activePlayer.value = next
        playbackActive.value = next.isPlaying
    }

    // --- Home-screen widget -------------------------------------------------
    // The widget mirrors the same state flow the in-app UI consumes. We only
    // re-render when the widget-relevant fields change (not on every 500ms
    // position tick) and cache the decoded artwork URI so it isn't reloaded for
    // a mere play/pause or progress update.
    private var lastWidgetArtUrl: String? = null
    private var lastWidgetArtUri: Uri? = null

    private fun startWidgetUpdates() {
        scope.launch {
            combine(
                state.map { NowPlayingWidget.snapshotFrom(it) }.distinctUntilChanged(),
                settings.themeChoice.distinctUntilChanged(),
            ) { snapshot, theme -> snapshot to theme }
                .collect { (snapshot, theme) ->
                    if (snapshot.imageUrl != lastWidgetArtUrl) {
                        lastWidgetArtUrl = snapshot.imageUrl
                        lastWidgetArtUri = snapshot.imageUrl?.let { loadWidgetArt(it) }
                    }
                    val palette = WidgetTheme.paletteFor(context, theme)
                    // Always send a complete RemoteViews tree with the artwork
                    // URI. Pixel Launcher can lose or ignore art after a fresh
                    // app process when progress-only partial updates become the
                    // latest host state; the URI payload is cheap enough to
                    // reassert on every widget tick.
                    NowPlayingWidget.render(context, snapshot, lastWidgetArtUri, palette)
                }
        }
    }

    /** Repaint the widget immediately from current state (e.g. when one is added). */
    fun refreshWidget() {
        val snapshot = NowPlayingWidget.snapshotFrom(state.value)
        val art = if (snapshot.imageUrl == lastWidgetArtUrl) lastWidgetArtUri else null
        val palette = WidgetTheme.paletteFor(context, settings.snapshot().themeChoice)
        NowPlayingWidget.render(context, snapshot, art, palette)
    }

    /**
     * Load the track artwork, cache it as a PNG, and return a FileProvider URI the
     * launcher can read. A URI (not an in-RemoteViews bitmap) is replayable from
     * disk, so the art survives the launcher re-inflating the widget and isn't
     * subject to the RemoteViews bitmap cache that was dropping it.
     */
    private suspend fun loadWidgetArt(url: String): Uri? {
        val authed = widgetArtUrl(url)
        val bitmap = runCatching {
            val request = ImageRequest.Builder(context)
                .data(authed)
                .allowHardware(false)
                .size(512)
                .build()
            when (val result = context.imageLoader.execute(request)) {
                is SuccessResult -> result.drawable.toBitmap()
                is ErrorResult -> {
                    Log.w(TAG, "Widget art failed: $authed", result.throwable)
                    null
                }
            }
        }.onFailure { Log.w(TAG, "Widget art error: $authed", it) }.getOrNull() ?: return null
        return runCatching {
            val dir = File(context.cacheDir, "widget_art").apply { mkdirs() }
            // Unique filename each load so the URI always changes — the launcher
            // caches images by URI and won't re-read a URI it has seen, so a stable
            // name leaves stale/blank art on screen. Prune older files (but only when
            // writing a new one, so the last art persists on disk while the app is
            // closed and the launcher can still reload it).
            val file = File(dir, "art_${System.currentTimeMillis()}_${url.hashCode().toUInt()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dir.listFiles()?.forEach { if (it.name != file.name) it.delete() }
            FileProvider.getUriForFile(context, "${context.packageName}.widgetart", file)
        }.onFailure { Log.w(TAG, "Widget art cache write failed", it) }.getOrNull()
    }

    /**
     * The widget loads art via Coil's default image loader, which has no Emby
     * auth interceptor — so append the token as `api_key` to be safe if the
     * server requires auth for images.
     */
    private fun widgetArtUrl(url: String): String {
        if (url.contains("api_key=")) return url
        val token = settings.snapshot().accessToken?.takeIf { it.isNotBlank() } ?: return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}api_key=$token"
    }

    fun playQueue(items: List<LibraryItem>, startItem: LibraryItem, source: PlaybackSource? = null) {
        setQueue(items = items, startItem = startItem, shuffled = false, playWhenReady = true, source = source)
    }

    fun prepareShuffledQueue(items: List<LibraryItem>, source: PlaybackSource? = null) {
        val tracks = items.shuffled(Random(System.nanoTime()))
        prepareQueue(items = tracks, shuffled = true, source = source)
    }

    fun prepareQueue(items: List<LibraryItem>, shuffled: Boolean, source: PlaybackSource? = null) {
        val startItem = items.firstOrNull() ?: return
        setQueue(
            items = items,
            startItem = startItem,
            shuffled = shuffled,
            playWhenReady = activePlayerRef.isPlaying,
            source = source,
        )
    }

    private fun setQueue(
        items: List<LibraryItem>,
        startItem: LibraryItem,
        shuffled: Boolean,
        playWhenReady: Boolean,
        source: PlaybackSource? = null,
    ) {
        val startIndex = items.indexOfFirst { it.id == startItem.id }.coerceAtLeast(0)
        val tracks = items.map { it.toPlaybackTrack() }
        if (tracks.isEmpty()) return
        recordRecentPlay(source, tracks)
        cancelCrossfade()
        suppressCrossfadeIndex = -1
        clearGuestDjState()
        // Guarantee audible playback for a new queue even if the volume was left
        // low by an interrupted ramp or external glitch (self-heals "silent but
        // advancing").
        player.volume = 1f
        reportStopped(lastReportedState)
        lastReportedState = PlaybackUiState()
        playSessionIdsByIndex = tracks.indices
            .associateWith { UUID.randomUUID().toString() }
            .toMutableMap()
        lastProgressReportMs = 0
        lastStartedItemId = null
        refreshHeaders()
        resetRepeatForNewSession()
        queue = tracks
        fetchLoudness(tracks.map { it.id })
        queueShuffled = shuffled
        streamOffsetsByIndex = tracks
            .mapIndexedNotNull { index, track ->
                track.streamStartOffset().takeIf { it > 0 }?.let { index to it }
            }
            .toMap()
            .toMutableMap()
        val resumePosition = tracks.getOrNull(startIndex)?.playerStartPosition(startIndex) ?: 0
        activePlayerRef.setMediaItems(
            activeMediaItems(),
            startIndex,
            if (isCasting) tracks[startIndex].absolutePosition(startIndex, resumePosition) else resumePosition,
        )
        applyPlaybackSpeed(startIndex)
        schedulePrefetch(startIndex)
        if (playWhenReady) {
            activePlayerRef.prepare()
            playActive()
            reportStarted(
                track = tracks[startIndex],
                positionMs = tracks[startIndex].absolutePosition(startIndex, resumePosition),
                index = startIndex,
            )
        } else {
            activePlayerRef.pause()
        }
        publishState()
        persistSession()
    }

    /** Record this queue in Recent plays, unless it's an audiobook or has no source. */
    private fun recordRecentPlay(source: PlaybackSource?, tracks: List<PlaybackTrack>) {
        if (source == null) return
        if (tracks.firstOrNull()?.isLongForm == true) return
        val trackIds = tracks.map { it.id }
        scope.launch {
            recentPlays.record(
                RecentPlay(
                    key = source.key,
                    title = source.title,
                    subtitle = source.subtitle,
                    coverUrl = source.coverUrl,
                    trackIds = trackIds,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    // --- Session persistence (resume after process death) ---------------------

    private fun PlaybackTrack.toPersisted(): PersistedTrack = PersistedTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        imageUrl = imageUrl,
        durationMs = durationMs,
        playbackPositionMs = playbackPositionMs,
        contentKind = contentKind.name,
    )

    private fun PersistedTrack.toPlaybackTrack(): PlaybackTrack = PlaybackTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        imageUrl = imageUrl,
        durationMs = durationMs,
        playbackPositionMs = playbackPositionMs,
        contentKind = runCatching { ContentKind.valueOf(contentKind) }.getOrDefault(ContentKind.UNKNOWN),
    )

    /**
     * Snapshot the current queue + position to disk so the widget/app can resume
     * after the process is killed. [throttle] coalesces the periodic calls from
     * the playing loop; discrete events (pause, skip, new queue) pass false.
     * Never persists while casting — the remote receiver owns playback then.
     */
    private fun persistSession(throttle: Boolean = false) {
        if (isCasting) return
        val tracks = queue
        if (tracks.isEmpty()) {
            scope.launch { runCatching { sessionStore.clear() } }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (throttle && now - lastSessionPersistMs < SESSION_PERSIST_THROTTLE_MS) return
        lastSessionPersistMs = now
        val index = activePlayerRef.currentMediaItemIndex.takeIf { it in tracks.indices } ?: 0
        val position = currentSessionPositionMs() ?: 0L
        val session = PersistedSession(
            tracks = tracks.map { it.toPersisted() },
            currentIndex = index,
            positionMs = position,
            shuffled = queueShuffled,
        )
        scope.launch { runCatching { sessionStore.save(session) } }
    }

    /**
     * Rebuild the last queue on cold start, paused at the saved position. Does no
     * network and no autoplay — the media items are set but not prepared, so
     * nothing streams until the user presses play. Bails if a live queue already
     * exists (a play beat the restore) or if casting.
     */
    private suspend fun restoreSession() {
        val saved = sessionStore.load() ?: return
        if (queue.isNotEmpty() || isCasting) return
        val restored = saved.tracks.map { it.toPlaybackTrack() }
        if (restored.isEmpty()) return
        val startIndex = saved.currentIndex.coerceIn(0, restored.lastIndex)
        // Resume the current track at the saved absolute position; the existing
        // offset/resume math then drives a local seek (music) or a server-side
        // StartTimeTicks stream (audiobooks).
        val tracks = restored.mapIndexed { i, track ->
            if (i == startIndex) track.copy(playbackPositionMs = saved.positionMs.coerceAtLeast(0L)) else track
        }
        queue = tracks
        queueShuffled = saved.shuffled
        playSessionIdsByIndex = tracks.indices
            .associateWith { UUID.randomUUID().toString() }
            .toMutableMap()
        streamOffsetsByIndex = tracks
            .mapIndexedNotNull { index, track ->
                track.streamStartOffset().takeIf { it > 0 }?.let { index to it }
            }
            .toMap()
            .toMutableMap()
        val resumePosition = tracks.getOrNull(startIndex)?.playerStartPosition(startIndex) ?: 0
        refreshHeaders()
        activePlayerRef.setMediaItems(activeMediaItems(), startIndex, resumePosition)
        applyPlaybackSpeed(startIndex)
        activePlayerRef.pause()
        publishState()
    }

    /** Join any in-flight cold-start restore so a widget command sees the queue. */
    private suspend fun awaitRestore() {
        restoreJob?.join()
    }

    /** Launch the app when a widget command has nothing to act on (no session). */
    private fun openApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    fun togglePlayPause() {
        if (queue.isEmpty()) {
            // Cold widget tap after a process kill: wait for the restore, then
            // resume — or open the app if there's genuinely nothing to resume.
            scope.launch {
                awaitRestore()
                if (queue.isEmpty()) openApp() else doTogglePlayPause()
            }
            return
        }
        doTogglePlayPause()
    }

    private fun doTogglePlayPause() {
        val player = activePlayerRef
        cancelCrossfade()
        if (player.isPlaying) {
            clearSleepTimer()
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
                player.prepare()
            }
            playActive()
        }
        publishState()
        reportProgress(
            state = lastReportedState,
            force = true,
            eventName = if (lastReportedState.isPlaying) "Unpause" else "Pause",
        )
        persistSession()
    }

    /** Pause local playback without tearing the session down (e.g. handing off to Cast). */
    fun pause() {
        if (!player.isPlaying) return
        cancelCrossfade()
        player.pause()
        publishState()
    }

    fun stopPlayback() {
        cancelCrossfade()
        // Stopping (closing Now Playing / mini-bar) forgets a music track's
        // position so it starts fresh next time — reportStopped never persists
        // a music position. Audiobooks keep their resume point.
        reportStopped(lastReportedState)
        lastReportedState = PlaybackUiState()
        activePlayerRef.stop()
        activePlayerRef.clearMediaItems()
        if (activePlayerRef !== player) {
            player.stop()
            player.clearMediaItems()
        }
        queue = emptyList()
        queueShuffled = false
        streamOffsetsByIndex.clear()
        prefetchJob?.cancel()
        prefetchSignature = null
        offlinePrefetch = OfflinePrefetchState()
        clearGuestDjState()
        clearSleepTimer()
        lastStartedItemId = null
        // Explicit Stop forgets the session: nothing to resume, widget clears.
        scope.launch { runCatching { sessionStore.clear() } }
        // Release the controller first; a live binding would keep the service
        // alive past stopService and leave a stale notification behind.
        releaseNotificationController()
        context.stopService(Intent(context, SonicPlaybackService::class.java))
        publishState()
    }

    fun seekTo(positionMs: Long) {
        val player = activePlayerRef
        cancelCrossfade()
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val track = queue.getOrNull(index)
        // Long-form always seeks server-side. So do transcoded tracks (e.g.
        // WMA→MP3): Emby serves them as a chunked stream of unknown length,
        // ExoPlayer marks the window unseekable, and an in-player seek on an
        // unseekable stream restarts from zero. Only trust the seekable flag
        // once READY — before that every stream looks unseekable.
        // A downloaded file is local and seekable, so seek it in-player even when
        // it's long-form (server-side seeking only applies to streamed content).
        val needsServerSeek = activePlayerRef === this.player && track != null &&
            !downloadStore.isTrackDownloaded(track.id) && (
            track.isLongForm ||
                (player.playbackState == Player.STATE_READY && !player.isCurrentMediaItemSeekable)
            )
        if (track != null && needsServerSeek) {
            seekViaStreamOffset(index = index, track = track, positionMs = positionMs)
        } else {
            player.seekTo(positionMs.coerceAtLeast(0))
        }
        // Suppress a crossfade when the user seeks INTO this track's blend window:
        // a manual jump to the end has no runway for the helper to preload and
        // shouldn't fire a blend (it just plays out into a normal transition).
        // Re-enabled automatically if they seek back out of the tail.
        val duration = player.duration.takeIf { it != C.TIME_UNSET }
        val crossfadeMs = settings.snapshot().crossfadeDurationMs.toLong()
        suppressCrossfadeIndex = if (
            track != null && !track.isLongForm && duration != null && duration > 0 &&
            positionMs > duration - crossfadeMs
        ) index else -1
        publishState()
        reportProgress(lastReportedState, force = true, eventName = "TimeUpdate")
        persistSession()
    }

    fun skipPrevious() {
        if (queue.isEmpty()) {
            scope.launch { awaitRestore(); if (queue.isEmpty()) openApp() else skipPrevious() }
            return
        }
        val player = activePlayerRef
        cancelCrossfade()
        clearSleepTimer()
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
        publishState()
        persistSession()
    }

    fun skipNext() {
        if (queue.isEmpty()) {
            scope.launch { awaitRestore(); if (queue.isEmpty()) openApp() else skipNext() }
            return
        }
        val player = activePlayerRef
        cancelCrossfade()
        clearSleepTimer()
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        publishState()
        persistSession()
    }

    fun seekToQueueIndex(index: Int) {
        if (index !in queue.indices) return
        cancelCrossfade()
        clearSleepTimer()
        reportStopped(lastReportedState)
        lastReportedState = PlaybackUiState()
        streamOffsetsByIndex.remove(index)
        playSessionIdsByIndex[index] = UUID.randomUUID().toString()
        activePlayerRef.replaceMediaItem(
            index,
            activeMediaItem(index),
        )
        activePlayerRef.seekTo(index, 0L)
        applyPlaybackSpeed(index)
        playActive()
        reportStarted(queue[index], 0L, index)
        schedulePrefetch(index)
        publishState()
        persistSession()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentIndex = activePlayerRef.currentMediaItemIndex.coerceAtLeast(0)
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return
        if (fromIndex <= currentIndex || toIndex <= currentIndex || fromIndex == toIndex) return
        cancelCrossfade()
        val indexed = queue.mapIndexed { index, track -> index to track }.toMutableList()
        val moved = indexed.removeAt(fromIndex)
        indexed.add(toIndex, moved)
        queue = indexed.map { it.second }
        remapPlaybackMetadata(indexed.map { it.first })
        activePlayerRef.moveMediaItem(fromIndex, toIndex)
        queueShuffled = true
        guestDjAttemptSignature = null
        schedulePrefetch(currentIndex)
        publishState()
    }

    fun removeQueueItem(index: Int) {
        val currentIndex = activePlayerRef.currentMediaItemIndex.coerceAtLeast(0)
        if (index !in queue.indices || index <= currentIndex) return
        cancelCrossfade()
        val indexed = queue.mapIndexed { oldIndex, track -> oldIndex to track }.toMutableList()
        indexed.removeAt(index)
        queue = indexed.map { it.second }
        remapPlaybackMetadata(indexed.map { it.first })
        activePlayerRef.removeMediaItem(index)
        guestDjAttemptSignature = null
        schedulePrefetch(currentIndex)
        publishState()
    }

    private fun remapPlaybackMetadata(oldIndicesByNewIndex: List<Int>) {
        val oldSessions = playSessionIdsByIndex.toMap()
        val oldOffsets = streamOffsetsByIndex.toMap()
        playSessionIdsByIndex = oldIndicesByNewIndex
            .mapIndexedNotNull { newIndex, oldIndex -> oldSessions[oldIndex]?.let { newIndex to it } }
            .toMap()
            .toMutableMap()
        streamOffsetsByIndex = oldIndicesByNewIndex
            .mapIndexedNotNull { newIndex, oldIndex -> oldOffsets[oldIndex]?.let { newIndex to it } }
            .toMap()
            .toMutableMap()
    }

    fun currentMetadataDurationMs(): Long? {
        val index = activePlayerRef.currentMediaItemIndex.coerceAtLeast(0)
        return queue.getOrNull(index)?.durationMs?.takeIf { it > 0L }
    }

    fun currentSessionPositionMs(): Long? {
        val player = activePlayerRef
        val index = player.currentMediaItemIndex.takeIf { it in queue.indices } ?: return null
        return queue[index].absolutePosition(index, player.currentPosition.coerceAtLeast(0L))
    }

    fun currentSessionBufferedPositionMs(): Long? {
        val player = activePlayerRef
        val index = player.currentMediaItemIndex.takeIf { it in queue.indices } ?: return null
        val absolute = queue[index].absolutePosition(index, player.bufferedPosition.coerceAtLeast(0L))
        return currentMetadataDurationMs()?.let { absolute.coerceAtMost(it) } ?: absolute
    }

    fun shuffleQueue() {
        if (queue.size < 2) return
        cancelCrossfade()
        val player = activePlayerRef
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val currentTrack = queue.getOrNull(currentIndex)
        val currentPlaybackSessionId = playSessionIdsByIndex[currentIndex]
        val currentPosition = player.currentPosition.coerceAtLeast(0)
        val wasPlaying = player.isPlaying
        val shuffledTail = queue
            .filterNot { it.id == currentTrack?.id }
            .shuffled(Random(System.nanoTime()))
        queue = if (currentTrack != null) listOf(currentTrack) + shuffledTail else shuffledTail
        queueShuffled = true
        guestDjAttemptSignature = null
        playSessionIdsByIndex = queue.indices
            .associateWith { index ->
                currentPlaybackSessionId.takeIf { index == 0 && currentTrack != null }
                    ?: UUID.randomUUID().toString()
            }
            .toMutableMap()
        streamOffsetsByIndex = queue
            .mapIndexedNotNull { index, track ->
                track.streamStartOffset().takeIf { it > 0 }?.let { index to it }
            }
            .toMap()
            .toMutableMap()
        player.setMediaItems(
            activeMediaItems(),
            0,
            currentPosition,
        )
        player.prepare()
        if (wasPlaying) playActive() else player.pause()
        schedulePrefetch(0)
        publishState()
        persistSession()
    }

    fun setSleepTimer(durationMs: Long) {
        require(durationMs > 0L) { "Sleep timer duration must be positive" }
        sleepTimerJob?.cancel()
        sleepTimerMode = SleepTimerMode.TIMED
        sleepTimerEndsAtMs = System.currentTimeMillis() + durationMs
        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = sleepTimerEndsAtMs - System.currentTimeMillis()
                if (remaining <= 0L) {
                    sleepTimerJob = null
                    fadeAndPauseForSleepTimer(completedTrack = false)
                    return@launch
                }
                publishState()
                delay(minOf(remaining, SLEEP_TIMER_TICK_MS))
            }
        }
        publishState()
    }

    fun setSleepTimerEndOfTrack() {
        sleepTimerJob?.cancel()
        sleepTimerMode = SleepTimerMode.END_OF_TRACK
        sleepTimerEndsAtMs = 0L
        publishState()
    }

    fun cancelSleepTimer() {
        clearSleepTimer()
        publishState()
    }

    fun setAudiobookSpeed(speed: Float) {
        scope.launch { settings.setAudiobookSpeed(speed) }
    }

    fun setGuestDjEnabled(enabled: Boolean) {
        val player = activePlayerRef
        val current = queue.getOrNull(player.currentMediaItemIndex.coerceAtLeast(0))
        guestDjEnabled = enabled &&
            player.repeatMode == Player.REPEAT_MODE_OFF &&
            current?.guestDjEligible == true
        guestDjLoading = false
        guestDjAttemptSignature = null
        publishState()
        if (guestDjEnabled) maybeInjectGuestDj()
    }

    fun cycleRepeatMode() {
        val player = activePlayerRef
        val nextMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> PlaybackRepeatMode.ALL
            Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ONE
            else -> PlaybackRepeatMode.OFF
        }
        player.repeatMode = nextMode.toPlayerRepeatMode()
        if (player !== this.player) this.player.repeatMode = nextMode.toPlayerRepeatMode()
        castPlayer?.takeIf { it !== player }?.repeatMode = nextMode.toPlayerRepeatMode()
        if (nextMode != PlaybackRepeatMode.OFF && guestDjEnabled) {
            guestDjEnabled = false
            guestDjLoading = false
            guestDjAttemptSignature = null
        }
        scope.launch { settings.setPlaybackRepeatMode(nextMode.name) }
        publishState()
    }

    private fun resetRepeatForNewSession() {
        player.repeatMode = Player.REPEAT_MODE_OFF
        castPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        scope.launch { settings.setPlaybackRepeatMode(PlaybackRepeatMode.OFF.name) }
    }

    private fun applyPlaybackSpeed(indexOverride: Int? = null) {
        val player = activePlayerRef
        val index = indexOverride ?: player.currentMediaItemIndex.coerceAtLeast(0)
        val speed = if (queue.getOrNull(index)?.contentKind == ContentKind.AUDIOBOOK) audiobookSpeed else 1f
        player.playbackParameters = PlaybackParameters(speed, 1f)
    }

    private fun mediaItem(
        track: PlaybackTrack,
        startOffsetMs: Long,
        playbackSessionId: String,
    ): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.imageUrl?.let(Uri::parse))
        val duration = track.durationMs?.takeIf { it > 0 }
        if (duration != null) {
            metadataBuilder.setDurationMs(duration)
            metadataBuilder.setExtras(
                Bundle().apply {
                    putString(PlatformMediaMetadata.METADATA_KEY_MEDIA_ID, track.id)
                    putString(PlatformMediaMetadata.METADATA_KEY_TITLE, track.title)
                    putString(PlatformMediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    putString(PlatformMediaMetadata.METADATA_KEY_ALBUM, track.album)
                    putLong(PlatformMediaMetadata.METADATA_KEY_DURATION, duration)
                },
            )
        } else {
            Log.w(TAG, "Track ${track.id} has no Emby duration for MediaSession metadata")
        }
        val metadata = metadataBuilder.build()
        // Prefer a persistently downloaded file (offline playback) for any track —
        // a local file is seekable, so long-form resume is handled by the player
        // rather than a server offset. Otherwise fall back to the transient prefetch
        // cache (track-start music only) or streaming.
        val localUri = downloadStore.localUri(track.id)
            ?: if (startOffsetMs == 0L && !track.isLongForm) prefetchCache.cachedUri(track.id) else null

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(localUri ?: Uri.parse(streamUrl(track.id, startOffsetMs, playbackSessionId)))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun activeMediaItems(): List<MediaItem> =
        if (isCasting) castMediaItems() else localMediaItems()

    private fun activeMediaItem(index: Int): MediaItem =
        if (isCasting) castMediaItem(index) else localMediaItem(index)

    private fun localMediaItems(): List<MediaItem> =
        queue.indices.map(::localMediaItem)

    private fun localMediaItem(index: Int): MediaItem =
        mediaItem(
            track = queue[index],
            startOffsetMs = streamOffsetsByIndex[index] ?: 0L,
            playbackSessionId = playbackSessionId(index),
        )

    private fun castMediaItems(): List<MediaItem> =
        queue.indices.map(::castMediaItem)

    private fun castMediaItem(index: Int): MediaItem {
        val track = queue[index]
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(castImageUrl(track.imageUrl)?.let(Uri::parse))
        track.durationMs?.takeIf { it > 0L }?.let { metadataBuilder.setDurationMs(it) }
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(castStreamUrl(track.id, playbackSessionId(index))))
            .setMimeType(CAST_MIME)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun streamUrl(itemId: String, startOffsetMs: Long, playbackSessionId: String): String {
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
            .appendQueryParameter("PlaySessionId", playbackSessionId)
        if (startOffsetMs > 0) {
            builder
                .appendQueryParameter("Static", "false")
                .appendQueryParameter("MediaSourceId", "mediasource_$itemId")
                .appendQueryParameter("StartTimeTicks", startOffsetMs.msToTicks().toString())
        }
        return builder.build().toString()
    }

    private fun castStreamUrl(itemId: String, playbackSessionId: String): String {
        val snap = settings.snapshot()
        val base = castBase()
            ?: throw IllegalStateException("No Cast server configured")
        val token = snap.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Not signed in")
        val userId = snap.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Not signed in")
        return Uri.parse("$base/Audio/${Uri.encode(itemId)}/universal")
            .buildUpon()
            .appendQueryParameter("UserId", userId)
            .appendQueryParameter("DeviceId", snap.deviceId)
            .appendQueryParameter("MaxStreamingBitrate", "140000000")
            .appendQueryParameter("Container", "mp3")
            .appendQueryParameter("AudioCodec", "mp3")
            .appendQueryParameter("TranscodingContainer", "mp3")
            .appendQueryParameter("TranscodingProtocol", "http")
            .appendQueryParameter("api_key", token)
            .appendQueryParameter("PlaySessionId", playbackSessionId)
            .build()
            .toString()
    }

    private fun castImageUrl(imageUrl: String?): String? {
        val url = imageUrl ?: return null
        val snap = settings.snapshot()
        val base = castBase()
        val rebased = if (base != null) {
            snap.serverUrl?.trimEnd('/')?.let { url.replace(it, base) } ?: url
        } else {
            url
        }
        val token = snap.accessToken?.takeIf { it.isNotBlank() } ?: return rebased
        if (rebased.contains("api_key=")) return rebased
        val separator = if (rebased.contains('?')) '&' else '?'
        return "$rebased${separator}api_key=$token"
    }

    private fun castBase(): String? {
        val snap = settings.snapshot()
        return snap.castServerUrl?.takeIf { it.isNotBlank() }?.trimEnd('/')
            ?: snap.serverUrl?.trimEnd('/')
    }

    private val isCasting: Boolean
        get() = activePlayerRef === castPlayer

    private fun localPlayerPosition(index: Int, absolutePositionMs: Long): Long =
        (absolutePositionMs - (streamOffsetsByIndex[index] ?: 0L)).coerceAtLeast(0L)

    private fun playbackSessionId(index: Int): String =
        playSessionIdsByIndex.getOrPut(index) { UUID.randomUUID().toString() }

    private fun refreshHeaders() {
        httpDataSourceFactory.setDefaultRequestProperties(playbackHeaders())
    }

    private fun playbackHeaders(): LinkedHashMap<String, String> {
        val snap = settings.snapshot()
        return linkedMapOf(
            "X-Emby-Authorization" to buildString {
                append("MediaBrowser ")
                append("Client=\"liquidWave\", ")
                append("Device=\"${Build.MODEL}\", ")
                append("DeviceId=\"${snap.deviceId}\", ")
                append("Version=\"${BuildConfig.VERSION_NAME}\"")
            },
        ).also { headers ->
            snap.accessToken?.takeIf { it.isNotBlank() }?.let { headers["X-Emby-Token"] = it }
        }
    }

    /** Start playback only after the foreground service/session are connected. */
    private fun playActive() {
        ensurePlaybackService()
        activePlayerRef.play()
    }

    private fun ensurePlaybackService() {
        val intent = Intent(context, SonicPlaybackService::class.java)
        ContextCompat.startForegroundService(context, intent)
        connectNotificationController()
    }

    private fun connectNotificationController() {
        if (notificationControllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, SonicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        notificationControllerFuture = future
        future.addListener(
            { runCatching { notificationController = future.get() } },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun releaseNotificationController() {
        notificationControllerFuture?.let { MediaController.releaseFuture(it) }
        notificationControllerFuture = null
        notificationController = null
    }

    private fun publishState() {
        val player = activePlayerRef
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val streamOffset = streamOffsetsByIndex[index] ?: 0L
        val position = streamOffset + player.currentPosition.coerceAtLeast(0)
        val originalDuration = queue.getOrNull(index)?.durationMs
        val currentTrack = queue.getOrNull(index)
        val duration = if (streamOffset > 0) {
            originalDuration ?: player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        } else {
            player.duration.takeIf { it != C.TIME_UNSET } ?: originalDuration ?: 0
        }
        val nextState = PlaybackUiState(
            currentTrack = currentTrack,
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
            sleepTimerMode = sleepTimerMode,
            sleepTimerRemainingMs = sleepTimerRemainingMs(),
            audiobookSpeed = audiobookSpeed,
            guestDjEnabled = guestDjEnabled,
            guestDjAvailable = currentTrack?.guestDjEligible == true && player.repeatMode == Player.REPEAT_MODE_OFF,
            guestDjLoading = guestDjLoading,
            isCasting = isCasting,
            castVolume = castVolume.takeIf { isCasting } ?: CastVolumeState(),
            offlinePrefetch = offlinePrefetch,
            crossfadeFromTrack = crossfadeFromTrack,
            crossfadeBlendMs = crossfadeBlendMs,
        )
        if (isCasting && currentTrack != null) {
            lastCastIndex = index
            lastCastPositionMs = position
        }
        val previous = lastReportedState
        if (previous.currentTrack?.id != null && previous.currentTrack.id != nextState.currentTrack?.id) {
            val completedByCrossfade = crossfadeInProgress &&
                crossfadeFromTrack?.id == previous.currentTrack.id &&
                crossfadeFromIndex == previous.currentIndex
            reportStopped(previous, completedByCrossfade = completedByCrossfade)
        }
        // Set the primary player's normalisation gain whenever the current track
        // changes (independent of play state, so it's correct before the first frame).
        if (nextState.currentTrack != null && nextState.currentTrack.id != lastNormalizedTrackId) {
            applyNormalizationGain(nextState.currentTrack.id)
        }
        if (nextState.currentTrack != null && nextState.currentTrack.id != lastStartedItemId && nextState.isPlaying) {
            reportStarted(nextState.currentTrack, nextState.positionMs, nextState.currentIndex)
        }
        if (nextState.currentTrack != null && previous.currentIndex != nextState.currentIndex) {
            applyPlaybackSpeed()
            schedulePrefetch(nextState.currentIndex)
        }
        lastReportedState = nextState
        _state.value = nextState
    }

    private fun maybeFireEndOfTrackSleepTimer() {
        if (sleepTimerMode != SleepTimerMode.END_OF_TRACK || sleepTimerFiring) return
        val state = lastReportedState
        val track = state.currentTrack ?: return
        if (track.contentKind != ContentKind.AUDIOBOOK) return
        if (state.durationMs <= 0L) return
        val remaining = state.durationMs - state.positionMs
        if (remaining in 1..SLEEP_TIMER_FADE_MS) {
            scope.launch { fadeAndPauseForSleepTimer(completedTrack = true) }
        }
    }

    private suspend fun fadeAndPauseForSleepTimer(completedTrack: Boolean) {
        if (sleepTimerFiring) return
        sleepTimerFiring = true
        val player = activePlayerRef
        val currentJob = currentCoroutineContext()[Job]
        sleepTimerJob?.takeIf { it != currentJob }?.cancel()
        sleepTimerJob = null
        cancelCrossfade()
        val startingVolume = player.volume.coerceIn(0f, 1f).takeIf { it > 0f } ?: 1f
        val steps = (SLEEP_TIMER_FADE_MS / SLEEP_TIMER_FADE_STEP_MS).toInt().coerceAtLeast(1)
        repeat(steps) { step ->
            val progress = (step + 1).toFloat() / steps
            player.volume = startingVolume * (1f - progress)
            delay(SLEEP_TIMER_FADE_STEP_MS)
        }
        if (completedTrack) {
            val state = lastReportedState
            reportStopped(state.copy(positionMs = state.durationMs.coerceAtLeast(state.positionMs)))
        }
        player.pause()
        player.volume = 1f
        sleepTimerFiring = false
        clearSleepTimer()
        publishState()
    }

    private fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerMode = SleepTimerMode.OFF
        sleepTimerEndsAtMs = 0L
    }

    private fun sleepTimerRemainingMs(): Long =
        when (sleepTimerMode) {
            SleepTimerMode.TIMED -> (sleepTimerEndsAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            SleepTimerMode.END_OF_TRACK -> {
                val state = lastReportedState
                (state.durationMs - state.positionMs).takeIf { state.durationMs > 0L }?.coerceAtLeast(0L) ?: 0L
            }
            SleepTimerMode.OFF -> 0L
        }

    // Normalized song identity for dedup across duplicate library items: the same
    // song can exist under several Emby item ids, so id alone isn't enough.
    private fun contentKey(title: String, artist: String?): String =
        "${title.trim().lowercase()} ${artist?.trim()?.lowercase().orEmpty()}"

    private fun PlaybackTrack.contentKey(): String = contentKey(title, artist)

    private fun LibraryItem.contentKey(): String = contentKey(title, subtitle)

    private fun maybeInjectGuestDj() {
        if (!guestDjEnabled || guestDjLoading) return
        val player = activePlayerRef
        if (player.repeatMode != Player.REPEAT_MODE_OFF) {
            clearGuestDjState()
            publishState()
            return
        }
        val index = player.currentMediaItemIndex.takeIf { it in queue.indices } ?: return
        val current = queue[index]
        if (!current.guestDjEligible) {
            clearGuestDjState()
            publishState()
            return
        }
        val remaining = queue.lastIndex - index
        if (remaining >= GUEST_DJ_TRIGGER_REMAINING) return
        val signature = "$index:${current.id}:${queue.size}:${queue.lastOrNull()?.id}"
        if (signature == guestDjAttemptSignature) return
        guestDjAttemptSignature = signature
        guestDjLoading = true
        publishState()
        scope.launch {
            val result = runCatching {
                coordinator.injectQueue(
                    QueueInjectRequestDto(
                        currentTrackId = current.id,
                        queueLength = GUEST_DJ_INJECT_COUNT,
                    ),
                ).injected.map { it.toLibraryItem() }.withArtwork()
            }
            result.onSuccess { injected ->
                // Dedupe by item id AND by a normalized title+artist key. A library
                // with duplicate files holds the same song under several item ids;
                // those are all maximally similar to the seed, so one inject batch
                // can return three "different" tracks that are really one song
                // (the 36/37/38 repeat). The content key rejects those too.
                val seenIds = queue.map { it.id }.toMutableSet()
                val seenContent = queue.mapTo(mutableSetOf()) { it.contentKey() }
                val additions = injected
                    .filter {
                        it.contentKind == ContentKind.MUSIC &&
                            seenIds.add(it.id) &&
                            seenContent.add(it.contentKey())
                    }
                    .take(GUEST_DJ_INJECT_COUNT)
                if (guestDjEnabled && additions.isNotEmpty()) {
                    appendGuestDjItems(additions)
                }
            }.onFailure { error ->
                Log.w(TAG, "Guest DJ injection failed", error)
            }
            guestDjLoading = false
            publishState()
        }
    }

    private fun appendGuestDjItems(items: List<LibraryItem>) {
        if (items.isEmpty()) return
        val tracks = items.map { it.toPlaybackTrack() }
        val startIndex = queue.size
        tracks.forEachIndexed { offset, _ ->
            playSessionIdsByIndex[startIndex + offset] = UUID.randomUUID().toString()
        }
        queue = queue + tracks
        fetchLoudness(tracks.map { it.id })
        activePlayerRef.addMediaItems(
            tracks.mapIndexed { offset, track ->
                val index = startIndex + offset
                if (isCasting) {
                    castMediaItem(index)
                } else {
                    mediaItem(track, startOffsetMs = 0L, playbackSessionId = playbackSessionId(index))
                }
            },
        )
        schedulePrefetch(activePlayerRef.currentMediaItemIndex.coerceAtLeast(0))
    }

    private suspend fun List<LibraryItem>.withArtwork(): List<LibraryItem> {
        val art = runCatching { library.artworkByIds(map { it.id }) }.getOrDefault(emptyMap())
        return map { item -> art[item.id]?.let { item.copy(imageUrl = it) } ?: item }
    }

    private fun clearGuestDjState() {
        guestDjEnabled = false
        guestDjLoading = false
        guestDjAttemptSignature = null
    }

    private val PlaybackTrack.guestDjEligible: Boolean
        get() = contentKind == ContentKind.MUSIC || (contentKind == ContentKind.UNKNOWN && !isLongForm)

    private fun schedulePrefetch(anchorIndex: Int) {
        if (isCasting) {
            prefetchJob?.cancel()
            prefetchSignature = null
            setOfflinePrefetchState(OfflinePrefetchState(OfflinePrefetchStatus.UNAVAILABLE))
            return
        }
        val queueSnapshot = queue
        val upcoming = queueSnapshot
            .drop(anchorIndex + 1)
            .take(prefetchAheadCount)
            .filterNot { it.isLongForm }
        if (upcoming.isEmpty()) {
            prefetchJob?.cancel()
            prefetchSignature = null
            setOfflinePrefetchState(OfflinePrefetchState())
            return
        }
        val readyIds = upcoming
            .filter { prefetchCache.isCached(it.id) }
            .mapTo(mutableSetOf()) { it.id }
        setOfflinePrefetchState(
            OfflinePrefetchState(
                status = if (readyIds.size == upcoming.size) {
                    OfflinePrefetchStatus.READY
                } else {
                    OfflinePrefetchStatus.WARMING
                },
                readyCount = readyIds.size,
                targetCount = upcoming.size,
            ),
        )
        val signature = buildString {
            append(anchorIndex)
            append(':')
            append(queueSnapshot.joinToString(",") { it.id })
        }
        if (signature == prefetchSignature) return
        prefetchSignature = signature
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            prefetchCache.deleteTracks(queueSnapshot.take(anchorIndex).map { it.id })
            // Those behind-anchor tracks had their MediaItems swapped to the local
            // cache files we just deleted. Revert them to streaming URLs so skipping
            // back re-streams instead of opening a missing file (ENOENT -> Source
            // error -> a player stuck in ERROR where play does nothing).
            if (anchorIndex > 0) {
                scope.launch { restoreStreamingItems(queueSnapshot, anchorIndex) }
            }
            val headers = playbackHeaders()
            upcoming.forEach { track ->
                val index = queueSnapshot.indexOfFirst { it.id == track.id }
                if (index <= anchorIndex) return@forEach
                val uri = prefetchCache.prefetch(
                    trackId = track.id,
                    streamUrl = streamUrl(track.id, 0L, UUID.randomUUID().toString()),
                    headers = headers,
                ) ?: return@forEach
                readyIds += track.id
                scope.launch {
                    if (prefetchSignature == signature) {
                        setOfflinePrefetchState(
                            OfflinePrefetchState(
                                status = if (readyIds.size == upcoming.size) {
                                    OfflinePrefetchStatus.READY
                                } else {
                                    OfflinePrefetchStatus.WARMING
                                },
                                readyCount = readyIds.size,
                                targetCount = upcoming.size,
                            ),
                        )
                    }
                }
                scope.launch {
                    if (queue.getOrNull(index)?.id == track.id && player.currentMediaItemIndex < index) {
                        Log.d(TAG, "Using prefetched file for ${track.id}: $uri")
                        player.replaceMediaItem(index, mediaItem(track, 0L, playbackSessionId(index)))
                    }
                }
            }
            scope.launch {
                if (prefetchSignature == signature && readyIds.isNotEmpty()) {
                    setOfflinePrefetchState(
                        OfflinePrefetchState(
                            status = OfflinePrefetchStatus.READY,
                            readyCount = readyIds.size,
                            targetCount = upcoming.size,
                        ),
                    )
                }
            }
        }
    }

    private fun setOfflinePrefetchState(next: OfflinePrefetchState) {
        if (offlinePrefetch == next) return
        offlinePrefetch = next
        val current = _state.value
        if (current.offlinePrefetch != next) {
            _state.value = current.copy(offlinePrefetch = next)
        }
    }

    /**
     * Rebuild the MediaItems for the already-played tracks before [beforeIndex]
     * as streaming URLs. Their prefetch cache files have just been deleted, so
     * [mediaItem] now resolves them to the network stream rather than a dangling
     * local file. Never touches the current item, so playback is not disturbed.
     */
    private fun restoreStreamingItems(queueSnapshot: List<PlaybackTrack>, beforeIndex: Int) {
        val current = player.currentMediaItemIndex
        for (index in 0 until beforeIndex.coerceAtMost(player.mediaItemCount)) {
            if (index == current) continue
            val track = queue.getOrNull(index) ?: continue
            if (queueSnapshot.getOrNull(index)?.id != track.id) continue
            player.replaceMediaItem(
                index,
                mediaItem(track, streamOffsetsByIndex[index] ?: 0L, playbackSessionId(index)),
            )
        }
    }

    /**
     * Self-heal a source error caused by a dangling local prefetch URI (the cache
     * file was evicted out from under a still-referenced MediaItem). Re-resolve the
     * current item to a streaming URL and resume, so the player never gets stuck in
     * ERROR — where play() is a no-op and only skipping forward escapes.
     */
    private fun recoverFromSourceError(error: PlaybackException) {
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) return
        val index = player.currentMediaItemIndex.takeIf { it in queue.indices } ?: return
        val wasPlaying = player.playWhenReady
        player.replaceMediaItem(
            index,
            mediaItem(queue[index], streamOffsetsByIndex[index] ?: 0L, playbackSessionId(index)),
        )
        player.prepare()
        if (wasPlaying) playActive()
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
        // Music never resumes from a stored position — only the live player
        // session carries one (pause, Android Auto handoff). Durable resume is
        // an audiobook/long-form concept; honouring server positions for music
        // replays stale skip/crossfade leftovers mid-song.
        if (!isLongForm) return 0
        val resume = playbackPositionMs.coerceAtLeast(0)
        val duration = durationMs
        if (duration != null && resume !in 1 until (duration - RESUME_END_PADDING_MS)) return 0
        return (resume - LONG_FORM_RESUME_PREROLL_MS).coerceAtLeast(0)
    }

    // Long-form (durable resume, server-offset /stream seek, no crossfade,
    // Played-on-completion) is driven by the explicit content kind when known.
    // Duration is only a fallback for UNKNOWN sources (e.g. mixed playlists) so a
    // long *music* track no longer masquerades as an audiobook (false resume,
    // suppressed crossfade) and a short audiobook chapter still resumes.
    private val PlaybackTrack.isLongForm: Boolean
        get() = when (contentKind) {
            ContentKind.AUDIOBOOK -> true
            ContentKind.MUSIC -> false
            ContentKind.UNKNOWN -> (durationMs ?: 0L) >= LONG_FORM_MIN_DURATION_MS
        }

    private fun PlaybackTrack.streamStartOffset(): Long =
        // A downloaded file is a normal seekable local file: no server-side offset;
        // the resume position is applied by the player instead (playerStartPosition).
        if (isLongForm && !downloadStore.isTrackDownloaded(id)) resumePositionForPlayback() else 0L

    private fun PlaybackTrack.playerStartPosition(index: Int): Long =
        resumePositionForPlayback() - (streamOffsetsByIndex[index] ?: 0L)

    private fun PlaybackTrack.absolutePosition(index: Int, playerPositionMs: Long): Long =
        (streamOffsetsByIndex[index] ?: 0L) + playerPositionMs

    /** Reopens the stream at [positionMs] via `/stream?StartTimeTicks=` (server-side seek). */
    private fun seekViaStreamOffset(index: Int, track: PlaybackTrack, positionMs: Long) {
        val nextOffset = positionMs.coerceAtLeast(0)
        val wasPlaying = player.isPlaying
        // Emby keys transcode jobs by PlaySessionId: re-requesting /stream with a
        // new StartTimeTicks but the same session returns the ALREADY-RUNNING job
        // — audio keeps coming from the old position while the position counter
        // shows the seek target. A fresh id forces a new job that honours the
        // offset (verified against Emby 4.10 for wma/mp3/m4b sources).
        playSessionIdsByIndex[index] = UUID.randomUUID().toString()
        streamOffsetsByIndex[index] = nextOffset
        player.replaceMediaItem(
            index,
            mediaItem(track, nextOffset, playbackSessionId(index)),
        )
        player.seekTo(index, 0L)
        player.prepare()
        if (wasPlaying) playActive() else player.pause()
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
        // Don't blend a track the user manually seeked to the end of.
        if (index == suppressCrossfadeIndex) return
        val current = queue.getOrNull(index) ?: return
        if (current.isLongForm) return
        if ((streamOffsetsByIndex[index] ?: 0L) > 0L) return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val next = queue.getOrNull(nextIndex) ?: return
        if (next.isLongForm) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val crossfadeMs = snap.crossfadeDurationMs.toLong()
        // Anchor the blend on where the music actually ENDS, not the file's
        // nominal end — otherwise a mastered fade-out means the blend spends
        // its window mixing near-silence (the "lull" at transitions, #38).
        val blendEnd = blendEndMs(current.id, duration)
        val tailFromMs = (blendEnd - crossfadeMs).coerceAtLeast(0)
        val remaining = blendEnd - player.currentPosition.coerceAtLeast(0)

        if (!crossfadeArmed && remaining in 1..(crossfadeMs + CROSSFADE_PRELOAD_MS)) {
            armCrossfade(current, tailFromMs, index)
        }
        if (!crossfadeArmed || crossfadeArmedIndex != index || crossfadeFireJob != null) return
        val helperHasTailBuffered = fadePlayerReady &&
            (fadePlayer?.bufferedPosition ?: 0L) >= tailFromMs + CROSSFADE_BUFFER_MARGIN_MS
        when {
            // Fire on a precise schedule, not on the next 50ms poll after the
            // blend point: the helper's tail starts at tailFromMs, so any firing
            // lag replays audio the primary has already played past that mark —
            // heard as a stutter at the start of the blend.
            helperHasTailBuffered && remaining - crossfadeMs <= CROSSFADE_FIRE_LEAD_MS ->
                scheduleCrossfadeFire(crossfadeMs, blendEnd)
            !helperHasTailBuffered && remaining <= MIN_CROSSFADE_START_MS -> {
                Log.w(TAG, "Crossfade helper was not ready; preserving normal transition")
                cancelCrossfade()
            }
        }
    }

    /**
     * Where the outgoing track's blend should complete: its measured effective
     * end (skipping a silent tail / the inaudible part of a fade-out), else the
     * file's full duration — which is also the fallback whenever the coordinator
     * hasn't measured the track or the user turned trimming off.
     */
    private fun blendEndMs(trackId: String, duration: Long): Long {
        if (!trimEdgesEnabled) return duration
        val end = edgesByTrackId[trackId]?.endMs ?: return duration
        // Never blend past the file, and ignore a degenerate value that would
        // leave no room for the blend.
        return end.coerceIn(1L, duration)
    }

    /**
     * Where the INCOMING track should start: past its leading silence, so the
     * blend fades in on music rather than dead air. 0 (the file's start) unless
     * a meaningful intro was measured — a sub-second trim isn't worth a seek.
     */
    private fun blendStartMs(trackId: String?): Long {
        if (!trimEdgesEnabled || trackId == null) return 0L
        val start = edgesByTrackId[trackId]?.startMs ?: return 0L
        return if (start >= CROSSFADE_MIN_INTRO_TRIM_MS) start else 0L
    }

    /**
     * Waits out the exact time to the blend point, then fires. Re-reads the live
     * position after every sleep so a stall/rebuffer during the wait can't make
     * the blend fire early; a pause cancels the job via cancelCrossfade.
     */
    private fun scheduleCrossfadeFire(crossfadeMs: Long, blendEndMs: Long) {
        // Lazy start so the job reference is assigned before the body can run
        // (Main.immediate would otherwise execute a no-wait fire synchronously
        // inside launch). endCrossfade owns cancelling/clearing the reference.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Phase 1: start the helper MUTED a little before the blend point.
            // A cold AudioTrack takes tens of ms to emit its first samples, so
            // swapping it in cold left a brief hole in the outgoing track at
            // the transition; rendering silently through the preroll makes the
            // volume swap at the blend point symmetric and instant.
            while (isActive) {
                if (player.duration == C.TIME_UNSET) break
                val wait = (blendEndMs - player.currentPosition) - crossfadeMs -
                    CROSSFADE_HELPER_PREROLL_MS
                if (wait <= 0) break
                delay(wait)
            }
            if (!crossfadeArmed) return@launch
            fadePlayer?.let {
                it.volume = 0f
                it.play()
            }
            // Phase 2: precise wait to the blend point itself.
            while (isActive) {
                if (player.duration == C.TIME_UNSET) break
                val wait = (blendEndMs - player.currentPosition) - crossfadeMs
                if (wait <= 0) break
                delay(wait)
            }
            if (!crossfadeArmed) return@launch
            val remaining = blendEndMs - player.currentPosition
            if (remaining <= 0) {
                // The track ended (or ran past its effective end) under us; let
                // the normal transition stand.
                cancelCrossfade()
                return@launch
            }
            fireCrossfade(blendDurationMs = remaining.coerceAtMost(crossfadeMs))
        }
        crossfadeFireJob = job
        job.start()
    }

    private fun armCrossfade(outgoing: PlaybackTrack, tailFromMs: Long, index: Int) {
        crossfadeArmed = true
        crossfadeArmedIndex = index
        fadePlayerReady = false
        // Use the normal direct-play source and seek to the tail. Reopening the
        // track through Emby's transcoder is too slow for a reliable handoff.
        // A fresh helper starts with playWhenReady=false, so the tail buffers
        // paused (no audio) until the blend point.
        val helper = fadePlayerInstance()
        helper.volume = 0f
        helper.playWhenReady = false
        // The helper plays the OUTGOING track's tail, so normalise it to that
        // track's level (fireCrossfade switches the primary to the incoming
        // track's gain when it advances).
        fadeGain.gain = normalizationGainFor(outgoing.id)
        // The helper is a concurrent Emby playback request. Reusing the
        // primary queue's PlaySessionId lets a helper transcode replace the
        // server-side stream context, so the primary's next item can receive
        // the wrong bytes and fail extraction during the handoff.
        helper.setMediaItem(
            mediaItem(
                track = outgoing,
                startOffsetMs = 0L,
                playbackSessionId = UUID.randomUUID().toString(),
            ),
        )
        // A preroll before the tail: the helper starts rendering this stretch
        // muted so its AudioTrack is warm when the blend point arrives.
        helper.seekTo((tailFromMs - CROSSFADE_HELPER_PREROLL_MS).coerceAtLeast(0))
        helper.prepare()
        Log.d(
            TAG,
            "Crossfade armed index=$index tailFromMs=$tailFromMs playWhenReady=${helper.playWhenReady}",
        )
    }

    private fun fireCrossfade(blendDurationMs: Long) {
        val helper = fadePlayer ?: run {
            // Helper went away (released) — fall back to a normal transition.
            cancelCrossfade()
            return
        }
        crossfadeInProgress = true
        crossfadeArmed = false
        crossfadeTargetIndex = player.nextMediaItemIndex
        // Capture the outgoing track before advancing so Now Playing can dissolve
        // its artwork over the blend (publishState reads these).
        crossfadeFromIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        crossfadeFromTrack = queue.getOrNull(crossfadeFromIndex)
        crossfadeBlendMs = blendDurationMs
        // The helper has been rendering muted since the preroll; make sure it is
        // actually running in case the fire happened before phase 1 could start
        // it (a late fire inside the blend window).
        if (!helper.isPlaying) helper.play()
        // Decoder offset between the two copies of the outgoing track — the
        // helper's AudioTrack start latency. The micro-swap below masks it.
        val helperOffsetMs = player.currentPosition - helper.currentPosition
        Log.d(TAG, "Crossfade fired durationMs=$blendDurationMs helperOffsetMs=$helperOffsetMs")
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            // Micro-crossfade the outgoing track from the primary to the warm
            // helper. Both are playing the same content a few tens of ms apart,
            // so a short equal-power swap hides the handoff and the offset —
            // a hard cut here was audible as a brief dip at the transition.
            val swapSteps = (CROSSFADE_SWAP_MS / CROSSFADE_RAMP_STEP_MS).toInt().coerceAtLeast(1)
            for (step in 1..swapSteps) {
                val f = step.toFloat() / swapSteps
                // LINEAR, not equal-power: the two players carry the SAME
                // (correlated) content here, so the halves must sum to unity —
                // an equal-power pair summed +3dB and was audible as a brief
                // loudness bump right at the blend point.
                helper.volume = f
                player.volume = 1f - f
                Log.d(
                    TAG,
                    "Blend swap $step/$swapSteps pVol=${player.volume} hVol=${helper.volume} " +
                        "hPos=${helper.currentPosition} hState=${helper.playbackState} " +
                        "hPlaying=${helper.isPlaying} fadeGain=${fadeGain.gain}",
                )
                delay(CROSSFADE_RAMP_STEP_MS)
            }
            player.volume = 0f
            // Apply the incoming track's normalisation gain only now that the
            // primary is silent and about to leave the outgoing track: the
            // playback thread can push the next item's first buffers through
            // the sink before the listener-driven publishState() applies it,
            // which briefly played the incoming at the outgoing track's gain.
            queue.getOrNull(crossfadeTargetIndex)?.let { applyNormalizationGain(it.id) }
            // Advance the primary; the helper keeps the outgoing track audible
            // while the next decoder becomes ready. Skip the seek if the track
            // ended naturally during the swap (the player already advanced).
            // Start the incoming track past its leading silence so the blend
            // fades in on music rather than dead air (#38).
            if (player.currentMediaItemIndex == crossfadeFromIndex) {
                val introMs = blendStartMs(queue.getOrNull(crossfadeTargetIndex)?.id)
                if (introMs > 0 && crossfadeTargetIndex in queue.indices) {
                    player.seekTo(crossfadeTargetIndex, introMs)
                } else {
                    player.seekToNextMediaItem()
                }
            }
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
            // The incoming ease is time-capped, not just fraction-based: the
            // outgoing's own mastered fade-out plus the incoming's intro make
            // the transition naturally quiet, so the incoming must reach full
            // volume fast to fill it. The outgoing ease keeps the fraction —
            // its only job is softening cold endings.
            val fadeInFraction = (CROSSFADE_MAX_FADE_IN_MS.toFloat() / blendDurationMs)
                .coerceAtMost(CROSSFADE_EDGE_FRACTION)
            for (step in 1..steps) {
                val f = step.toFloat() / steps
                // Both tracks mix at FULL volume through the middle of the
                // blend: tracks usually carry their own mastered fade-out and
                // intro, so shaping the whole blend double-fades them and
                // audibly dips the combined level. Only the edges are eased so
                // a cold start or ending can't produce a hard seam.
                val fadeIn = (f / fadeInFraction).coerceAtMost(1f)
                val fadeOut = ((1f - f) / CROSSFADE_EDGE_FRACTION).coerceAtMost(1f)
                player.volume = sin(fadeIn * (PI.toFloat() / 2f))
                helper.volume = sin(fadeOut * (PI.toFloat() / 2f))
                if (step % 16 == 1) {
                    Log.d(
                        TAG,
                        "Blend trace f=$f pVol=${player.volume} hVol=${helper.volume} " +
                            "pPos=${player.currentPosition} hPos=${helper.currentPosition} " +
                            "pState=${player.playbackState} hState=${helper.playbackState} " +
                            "hPlaying=${helper.isPlaying} mainGain=${mainGain.gain} " +
                            "fadeGain=${fadeGain.gain}",
                    )
                }
                delay(CROSSFADE_RAMP_STEP_MS)
            }
            endCrossfade()
        }
    }

    private fun endCrossfade() {
        crossfadeFireJob?.cancel()
        crossfadeFireJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        // Fully release the helper so no second decoder lingers during normal
        // single-track playback.
        releaseFadePlayer()
        player.volume = 1f
        crossfadeInProgress = false
        crossfadeArmed = false
        crossfadeArmedIndex = -1
        crossfadeTargetIndex = -1
        crossfadeFromTrack = null
        crossfadeFromIndex = -1
        crossfadeBlendMs = 0
        publishState()
    }

    /** Aborts any armed or in-flight crossfade and restores the primary's volume. */
    private fun cancelCrossfade() {
        if (!crossfadeInProgress && !crossfadeArmed) return
        endCrossfade()
    }

    /**
     * Linear gain for a track from its measured loudness, levelling toward
     * [NORMALIZATION_TARGET_LUFS]. Returns 1.0 (unity) when normalisation is off
     * or we have no loudness for the track. The dB range is clamped so a very
     * quiet track gets a sane boost cap and a very loud one isn't crushed.
     */
    private fun normalizationGainFor(trackId: String): Float {
        if (!normalizationEnabled) return 1f
        val lufs = lufsByTrackId[trackId] ?: return 1f
        val gainDb = (NORMALIZATION_TARGET_LUFS - lufs)
            .coerceIn(NORMALIZATION_MIN_GAIN_DB, NORMALIZATION_MAX_GAIN_DB)
        return 10.0.pow(gainDb / 20.0).toFloat()
    }

    /** Apply the primary player's gain for [trackId] (called on each track change). */
    private fun applyNormalizationGain(trackId: String) {
        lastNormalizedTrackId = trackId
        mainGain.gain = normalizationGainFor(trackId)
    }

    /** Fetch loudness for a queue's tracks (best-effort; no coordinator → no-op). */
    private fun fetchLoudness(ids: List<String>) {
        val unknown = ids.filter { it !in lufsByTrackId }.distinct()
        if (unknown.isEmpty()) return
        scope.launch {
            val response = runCatching { coordinator.loudness(LoudnessRequestDto(unknown)) }.getOrNull()
                ?: return@launch
            // One call returns both loudness (volume normalisation) and edges
            // (crossfade trimming); either map may be empty independently, so
            // don't bail early on one being absent.
            lufsByTrackId.putAll(response.loudness)
            edgesByTrackId.putAll(response.edges)
            // Re-apply for whatever is playing now that its loudness may be known.
            lastReportedState.currentTrack?.let { applyNormalizationGain(it.id) }
        }
    }

    private fun reportStarted(track: PlaybackTrack, positionMs: Long, index: Int) {
        lastStartedItemId = track.id
        val sessionId = playbackSessionId(index)
        scope.launch {
            runCatching {
                embyApi.reportPlaybackStarted(
                    PlaybackReportDto(
                        itemId = track.id,
                        positionTicks = positionMs.msToTicks(),
                        playSessionId = sessionId,
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
        val sessionId = playbackSessionId(state.currentIndex)
        scope.launch {
            runCatching {
                embyApi.reportPlaybackProgress(
                    PlaybackReportDto(
                        itemId = track.id,
                        positionTicks = state.positionMs.msToTicks(),
                        playSessionId = sessionId,
                        isPaused = !state.isPlaying,
                        playlistIndex = state.currentIndex,
                        playlistLength = state.queue.size,
                        eventName = eventName,
                    ),
                )
            }
            // Durable resume is written for long-form only. Music must never
            // accumulate server-side positions (stale skip/crossfade leftovers
            // would replay mid-song), and a rolling Played=false write would
            // wipe played status earned in other sessions/clients.
            if (track.isLongForm) {
                val synced = runCatching { syncLongFormResume(track, state.positionMs) }.isSuccess
                recordDownloadProgress(track, state.positionMs, played = false, synced = synced)
            }
        }
    }

    /** Persist a downloaded long-form position locally; flush any offline backlog when online. */
    private suspend fun recordDownloadProgress(
        track: PlaybackTrack,
        positionMs: Long,
        played: Boolean,
        synced: Boolean,
        flushNow: Boolean = false,
    ) {
        if (!downloadStore.isTrackDownloaded(track.id)) return
        val pos = if (played) 0L else durableResumePosition(track, positionMs)
        // The book's chapter order is the current queue (downloaded long-form items),
        // so advancing keeps a single resume point and earlier chapters don't pull
        // resume backward — locally or, after flush, on Emby.
        val orderedIds = queue.filter { it.isLongForm && downloadStore.isTrackDownloaded(it.id) }.map { it.id }
        downloadProgress.setBookmark(orderedIds, track.id, pos, played, syncedToServer = synced, flushNow = flushNow)
        if (synced) flushPendingProgress()
    }

    /** Push positions saved while offline up to Emby once a write succeeds (we're online). */
    private fun flushPendingProgress() {
        val pending = downloadProgress.unsynced()
        if (pending.isEmpty()) return
        scope.launch {
            pending.forEach { (itemId, prog) ->
                val ok = runCatching {
                    writeUserDataById(itemId, prog.positionMs, prog.played)
                }.isSuccess
                if (ok) downloadProgress.markSynced(itemId)
            }
        }
    }

    /**
     * Final report for a track that is no longer current (transitioned away,
     * stopped, or replaced by a new queue). Completion — natural end, or a
     * crossfade handoff that fires up to the fade duration early — marks the
     * item Played with its position cleared, for both music and audiobook
     * chapters; chapter completion is what lets a book resume from the right
     * chapter when no chapter holds a mid position. A music track stopped
     * mid-way reports position 0 (skipped is skipped) and leaves UserData
     * untouched so earlier played status survives.
     */
    private fun reportStopped(state: PlaybackUiState, completedByCrossfade: Boolean = false) {
        val track = state.currentTrack ?: return
        val completed = track.isCompletedAt(state.positionMs, completedByCrossfade)
        val reportPositionMs = if (completed || track.isLongForm) state.positionMs else 0L
        val sessionId = playbackSessionId(state.currentIndex)
        scope.launch {
            runCatching {
                embyApi.reportPlaybackStopped(
                    PlaybackReportDto(
                        itemId = track.id,
                        positionTicks = reportPositionMs.msToTicks(),
                        playSessionId = sessionId,
                        isPaused = true,
                        playlistIndex = state.currentIndex,
                        playlistLength = state.queue.size,
                    ),
                )
            }
            val synced = when {
                completed -> runCatching { writeUserData(track, positionMs = 0L, played = true) }.isSuccess
                track.isLongForm -> runCatching { syncLongFormResume(track, state.positionMs) }.isSuccess
                else -> true
            }
            // A stop/transition is a good moment to persist the final position to disk.
            if (completed || track.isLongForm) {
                recordDownloadProgress(track, state.positionMs, played = completed, synced = synced, flushNow = true)
            }
        }
    }

    private fun PlaybackTrack.isCompletedAt(positionMs: Long, completedByCrossfade: Boolean): Boolean {
        val duration = durationMs ?: return false
        if (duration <= 0) return false
        var threshold = RESUME_END_PADDING_MS
        if (completedByCrossfade && !isLongForm) {
            // Only a blend that actually fired earns early completion credit.
            // Merely enabling crossfade must not turn a manual near-end stop or
            // skip into a completed listen.
            threshold = maxOf(threshold, crossfadeBlendMs + CROSSFADE_COMPLETION_SLACK_MS)
        }
        return positionMs >= duration - threshold
    }

    private suspend fun syncLongFormResume(track: PlaybackTrack, positionMs: Long) {
        writeUserData(track, durableResumePosition(track, positionMs), played = false)
    }

    private suspend fun writeUserData(track: PlaybackTrack, positionMs: Long, played: Boolean) =
        writeUserDataById(track.id, positionMs, played)

    private suspend fun writeUserDataById(itemId: String, positionMs: Long, played: Boolean) {
        val userId = settings.snapshot().userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Not signed in")
        embyApi.updateUserData(
            userId = userId,
            itemId = itemId,
            body = UserDataUpdateDto(
                playbackPositionTicks = positionMs.msToTicks(),
                played = played,
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
        // Volume normalisation target + safe gain range (EBU R128 LUFS / dB).
        // -14 LUFS is the common streaming reference; most masters sit louder than
        // this and are gently attenuated, while quiet tracks are boosted up to a
        // capped amount to avoid pumping or clipping.
        const val NORMALIZATION_TARGET_LUFS = -14.0
        const val NORMALIZATION_MIN_GAIN_DB = -15.0
        const val NORMALIZATION_MAX_GAIN_DB = 6.0
        const val PROGRESS_REPORT_INTERVAL_MS = 3_000L
        const val RESUME_MIN_POSITION_MS = 5_000L
        const val RESUME_END_PADDING_MS = 5_000L
        const val LONG_FORM_MIN_DURATION_MS = 20 * 60 * 1000L
        const val LONG_FORM_RESUME_PREROLL_MS = 5_000L
        const val CROSSFADE_POLL_MS = 50L
        const val CROSSFADE_RAMP_STEP_MS = 25L
        const val CROSSFADE_READY_POLL_MS = 10L
        const val CROSSFADE_INCOMING_READY_TIMEOUT_MS = 1_500L
        const val CROSSFADE_PRELOAD_MS = 12_000L
        const val CROSSFADE_BUFFER_MARGIN_MS = 500L
        // How far ahead of the blend point a buffered helper may schedule its
        // precisely-timed fire. Must exceed the helper preroll so phase 1 can
        // start the muted helper on time; kept short so playback stalls can't
        // accrue much drift between scheduling and firing (re-checked anyway).
        const val CROSSFADE_FIRE_LEAD_MS = 800L
        // Muted helper run-up before the blend point, warming its AudioTrack so
        // the handoff swap has no cold-start hole in the outgoing audio.
        const val CROSSFADE_HELPER_PREROLL_MS = 500L
        // Length of the equal-power handoff swap from primary to helper.
        const val CROSSFADE_SWAP_MS = 100L
        // Only skip an incoming track's intro if it's worth a seek — trimming a
        // couple of hundred ms costs a rebuffer for something nobody hears.
        const val CROSSFADE_MIN_INTRO_TRIM_MS = 500L
        const val MIN_CROSSFADE_START_MS = 2_000L
        const val CROSSFADE_COMPLETION_SLACK_MS = 1_000L
        // Fraction of the blend spent easing each edge (incoming in at the
        // start, outgoing out at the end); the middle mixes both at full volume.
        const val CROSSFADE_EDGE_FRACTION = 0.3f
        // Hard cap on the incoming ease-in so the next track fills the
        // transition quickly even on long blends.
        const val CROSSFADE_MAX_FADE_IN_MS = 750L
        const val PREFETCH_AHEAD_COUNT = 3
        const val GUEST_DJ_TRIGGER_REMAINING = 3
        const val GUEST_DJ_INJECT_COUNT = 5
        const val CAST_MIME = "audio/mpeg"
        const val CAST_VOLUME_DEBOUNCE_MS = 80L
        const val CAST_VOLUME_PENDING_GRACE_MS = 1_500L
        const val CAST_VOLUME_RECONCILE_TOLERANCE = 0.015f
        const val SLEEP_TIMER_TICK_MS = 1_000L
        const val SLEEP_TIMER_FADE_MS = 3_000L
        const val SLEEP_TIMER_FADE_STEP_MS = 100L
        const val SESSION_PERSIST_THROTTLE_MS = 10_000L
        const val TAG = "PlaybackController"
    }
}
