package guru.liquid.embysonic.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import guru.liquid.embysonic.R
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import dagger.hilt.android.AndroidEntryPoint
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDto
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.STATION_DECADES
import guru.liquid.embysonic.data.emby.preferredLibrary
import guru.liquid.embysonic.data.emby.resumeStartItem
import guru.liquid.embysonic.data.recent.RecentPlay
import guru.liquid.embysonic.data.recent.RecentPlaysRepository
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.MainActivity
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import javax.inject.Inject

@AndroidEntryPoint
class SonicPlaybackService : MediaLibraryService() {
    @Inject
    lateinit var playback: PlaybackController

    @Inject
    lateinit var library: LibraryRepository

    @Inject
    lateinit var coordinator: CoordinatorApi

    @Inject
    lateinit var recentPlays: RecentPlaysRepository

    @Inject
    lateinit var settings: SettingsRepository

    private var mediaSession: MediaLibrarySession? = null
    private var sessionPlayer: Player? = null

    // Non-null only while casting. Held separately because the receiver's volume
    // has to be pushed into the session's volume provider by hand.
    private var remoteVolumePlayer: RemoteVolumePlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundWatchdogPending = false
    private var castKeeperJob: Job? = null

    /**
     * Android kills the process with an ANR when a `startForegroundService()` call
     * isn't matched by `startForeground()` in time. Media3 normally satisfies that
     * by posting the media notification as soon as the player has something to
     * play — but when playback never actually starts (a failed track load, a dry
     * radio queue, a network drop) no notification is posted and the app is killed.
     * Seen in the field on beta.28 mid-ride: "Context.startForegroundService() did
     * not then call Service.startForeground()".
     *
     * This is the safety net. Shortly after each start, if Media3 still hasn't
     * posted, we post a minimal notification ourselves so the contract is met. It
     * reuses Media3's own notification id, so the real notification replaces this
     * placeholder the moment playback does begin. In the normal case the watchdog
     * finds a notification already posted and does nothing at all.
     */
    private val foregroundWatchdog = Runnable {
        foregroundWatchdogPending = false
        if (hasPostedMediaNotification()) return@Runnable
        Log.w(TAG, "Media3 posted no notification after service start; promoting with a placeholder")
        runCatching { promoteToForegroundWithPlaceholder() }
            .onFailure { Log.w(TAG, "Foreground watchdog could not promote the service", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (!foregroundWatchdogPending) {
            foregroundWatchdogPending = true
            mainHandler.postDelayed(foregroundWatchdog, FOREGROUND_WATCHDOG_DELAY_MS)
        }
        return result
    }

    private fun hasPostedMediaNotification(): Boolean =
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                ?.any { it.id == MEDIA_NOTIFICATION_ID } == true
        }.getOrDefault(false)

    private fun promoteToForegroundWithPlaceholder(contentTitle: String = getString(R.string.app_name)) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(MEDIA_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    MEDIA_CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(this, MEDIA_CHANNEL_ID)
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle(contentTitle)
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this,
            MEDIA_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /**
     * Keep the service in the foreground for as long as we are casting.
     *
     * While casting, no audio plays on the phone, so Media3 does not keep a media
     * notification up — and without one the process has no foreground service.
     * Android then treats the app as fully idle, freezes it, and eventually kills
     * it. Observed in the field 2026-08-23 on beta.34 (the app build then current;
     * beta.35/36 were coordinator-only and identical app-side):
     *
     *     ActivityManager: Killing guru.liquid.embysonic (adj 900):
     *                      Async binder space running out while frozen
     *
     * It surfaced only then because it was previously MASKED BY A BUG. Before
     * beta.34 the stock Cast MediaItemConverter crashed the app repeatedly during a
     * cast — 99 caught NPEs in one session once it was fixed — and every crash
     * restarted the process, resetting the idle timer that leads to the freeze.
     * Fixing the crash let the app live long enough to be frozen and killed
     * instead. Do not read the absence of earlier reports as this being new.
     *
     * Note *why* it dies: a frozen process cannot drain binder transactions, and an
     * actively-playing receiver keeps sending status callbacks, so the queue
     * overflows. Casting is therefore not merely permitted to be killed — the
     * longer the cast runs, the more certain the kill becomes.
     *
     * Three symptoms, one cause. The frozen process cannot run its periodic session
     * save, so you lose your place (playback resumes at the START of the queue next
     * launch). It cannot process Cast callbacks, so it dies. And once dead nothing
     * controls the receiver, which plays on with no way to stop it from the phone —
     * a cast ran for about an hour that way.
     *
     * Media3 owns the notification whenever it wants it, so this only steps in when
     * none is posted, and reuses Media3's own id so there is never a second one.
     * It re-checks periodically because Media3 may drop the notification at any
     * point during a cast, not only at the start.
     */
    private fun startCastForegroundKeeper() {
        if (castKeeperJob?.isActive == true) return
        castKeeperJob = serviceScope.launch {
            while (isActive) {
                if (!hasPostedMediaNotification()) {
                    Log.i(TAG, "Casting with no media notification; holding the service in the foreground")
                    runCatching { promoteToForegroundWithPlaceholder(getString(R.string.casting_notification_title)) }
                        .onFailure { Log.w(TAG, "Could not hold the service in the foreground while casting", it) }
                }
                delay(CAST_FOREGROUND_CHECK_MS)
            }
        }
    }

    private fun stopCastForegroundKeeper() {
        castKeeperJob?.cancel()
        castKeeperJob = null
    }

    override fun onCreate() {
        super.onCreate()
        // Without a session activity, tapping the media notification does nothing.
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val player = sessionPlayerFor(playback.activePlayerSnapshot())
        sessionPlayer = player
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            // Shuffle + repeat buttons for Android Auto and the notification shade.
            .setCustomLayout(buildCustomLayout())
            .build()
        serviceScope.launch {
            playback.activePlayer.collect { active ->
                val next = sessionPlayerFor(active)
                sessionPlayer = next
                mediaSession?.setPlayer(next)
            }
        }
        // Keep the shuffle/repeat button icons in sync when the mode changes from
        // anywhere (the phone UI, the buttons themselves), so the car shows it live.
        serviceScope.launch {
            playback.state
                .map { it.shuffleEnabled to it.repeatMode }
                .distinctUntilChanged()
                .collect { mediaSession?.setCustomLayout(buildCustomLayout()) }
        }
        // Keep the session's remote volume provider showing the receiver's real
        // level. CastPlayer never emits onDeviceVolumeChanged, so without this the
        // provider would still report whatever it read when casting started —
        // wrong after the in-app slider, another phone, or the TV remote moved it,
        // and the next key press would then jump from that stale value.
        serviceScope.launch {
            playback.state
                .map { it.castVolume }
                .distinctUntilChanged()
                // Only while the receiver's volume is actually known. Disconnecting
                // resets this to its 1.0 default *before* the player swaps back to
                // local, so without the guard the last thing pushed to the session
                // is a spurious "volume is now 100%" — visible as the cast volume
                // flashing to full in the system UI on the way out.
                .collect { volume -> if (volume.available) remoteVolumePlayer?.notifyVolumeChanged() }
        }
        // Hold the foreground service for the duration of a cast — see
        // startCastForegroundKeeper for why nothing else does.
        serviceScope.launch {
            playback.state
                .map { it.isCasting }
                .distinctUntilChanged()
                .collect { casting ->
                    if (casting) startCastForegroundKeeper() else stopCastForegroundKeeper()
                }
        }
    }

    /**
     * The custom control-row buttons AA and the notification render beyond the
     * native play/prev/next. Icons reflect the live state: shuffle on/off (a press
     * reshuffles — [PlaybackController.shuffleQueue] is one-shot, not a toggle) and
     * repeat off/all/one.
     */
    private fun buildCustomLayout(): ImmutableList<CommandButton> {
        val s = playback.state.value
        val shuffle = CommandButton.Builder(
            if (s.shuffleEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
        )
            .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
            .setDisplayName("Shuffle")
            .build()
        val repeat = CommandButton.Builder(
            when (s.repeatMode) {
                PlaybackRepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE
                PlaybackRepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setSessionCommand(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
            .setDisplayName("Repeat")
            .build()
        return ImmutableList.of(shuffle, repeat)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        mainHandler.removeCallbacks(foregroundWatchdog)
        foregroundWatchdogPending = false
        mediaSession?.release()
        mediaSession = null
        sessionPlayer = null
        remoteVolumePlayer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        // Grant the shuffle/repeat custom commands on top of the defaults, else the
        // custom-layout buttons stay disabled and never appear.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val default = super.onConnect(session, controller)
            val commands = default.availableSessionCommands.buildUpon()
                .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(buildCustomLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            // Callbacks run on the main thread, so touching the player here is safe.
            when (customCommand.customAction) {
                ACTION_SHUFFLE -> playback.shuffleQueue()
                ACTION_REPEAT -> playback.cycleRepeatMode()
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED),
                )
            }
            // The state collector will also refresh, but update now so the icon
            // flips immediately on tap rather than one state-emission later.
            mediaSession?.setCustomLayout(buildCustomLayout())
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        /**
         * Give Android Auto the last session so it can carry on without the app
         * being opened.
         *
         * This is a `default` method on Media3's callback, so leaving it out
         * compiled and ran perfectly happily — it just answered "unsupported", and
         * Auto fell back to offering *Open liquidWave*. Nothing logged, nothing
         * failed; the feature simply wasn't there. The same gap leaves the widget
         * showing "Tap to open" after the process dies.
         *
         * Failing the future is the documented way to say "nothing to resume", and
         * is the right answer for a first run or after the queue is cleared — Auto
         * then shows its normal empty state rather than a broken-looking entry.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val resumable = runCatching { playback.resumableSession() }
                    .onFailure { Log.w(TAG, "Could not rebuild the last session for resumption", it) }
                    .getOrNull()
                if (resumable == null) {
                    future.setException(UnsupportedOperationException("No previous session to resume"))
                } else {
                    Log.i(TAG, "Resuming last session for ${controller.packageName}")
                    future.set(resumable)
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            futureResult {
                LibraryResult.ofItem(mediaItemForId(mediaId) ?: browsableItem(mediaId, "liquidWave"), null)
            }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            futureResult {
                LibraryResult.ofItemList(childrenFor(parentId).paged(page, pageSize), params)
            }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            futureValue {
                val id = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaId ?: mediaItems.firstOrNull()?.mediaId
                if (id != null && playAutoItem(id)) {
                    val active = playback.activePlayerSnapshot()
                    MediaSession.MediaItemsWithStartPosition(currentPlayerItems(), active.currentMediaItemIndex, C.TIME_UNSET)
                } else {
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                }
            }
    }

    /**
     * Wraps the active player for the media session. While casting it gets a
     * second wrapper that publishes a working remote volume provider, which is
     * what makes the hardware volume keys reach the receiver — see
     * [RemoteVolumePlayer] for why Media3's CastPlayer needs the help.
     */
    private fun sessionPlayerFor(player: Player): Player {
        val timed = AvrcpDurationPlayer(
            player = player,
            fallbackDurationMs = { playback.currentMetadataDurationMs() },
            positionMs = { playback.currentSessionPositionMs() },
            bufferedPositionMs = { playback.currentSessionBufferedPositionMs() },
            onSeekToMs = playback::seekTo,
        )
        if (!playback.isRemotePlayer(player)) {
            remoteVolumePlayer = null
            return timed
        }
        return RemoteVolumePlayer(
            player = timed,
            currentVolume = { playback.state.value.castVolume.volume },
            currentMuted = { playback.state.value.castVolume.muted },
            onSetVolume = playback::setCastVolume,
            onSetMuted = playback::setCastMuted,
        ).also { remoteVolumePlayer = it }
    }

    private suspend fun childrenFor(parentId: String): List<MediaItem> =
        when (parentId) {
            // Auto renders the root's children as tabs. Four fit; a fifth starts a
            // "More" overflow (the old seven collapsed into "Recent plays, Sonic
            // Mixes, Stations, More", which buried the library). Stations has its
            // own tab so it isn't lost at the bottom of Home.
            ROOT_ID -> listOf(
                browsableItem(HOME_ID, "Home", R.drawable.ic_auto_home),
                browsableItem(STATIONS_ID, "Stations", R.drawable.ic_auto_stations),
                browsableItem(LIBRARY_ID, "Library", R.drawable.ic_auto_library),
                browsableItem(AUDIOBOOKS_ID, "Audiobooks", R.drawable.ic_auto_audiobooks),
            )
            HOME_ID -> homeChildren()
            LIBRARY_ID -> listOf(
                browsableItem(ALBUMS_ID, "Albums", R.drawable.auto_art_lib_albums),
                browsableItem(ARTISTS_ID, "Artists", R.drawable.auto_art_lib_artists),
                browsableItem(PLAYLISTS_ID, "Playlists", R.drawable.auto_art_lib_playlists),
            )
            AUDIOBOOKS_ID -> audiobooksChildren()
            // The Stations tab. Library/Random are playable (one tap = music); Genres
            // needs a choice so it browses one level deeper. The decades sit right
            // here as tiles: "80s" is one tap, not Decade Radio -> 80s. Sonic Adventure
            // and the Artist Mix Creator are deliberately absent: both need a
            // multi-item selection that a browse tree can't express (and shouldn't,
            // at speed).
            STATIONS_ID -> stationsChildren()
            DECADES_ID -> STATION_DECADES.map { stationItem(DECADE_PREFIX + it, "${it}s") }
            GENRES_ID -> library.genres(musicLibraryId()).map { it.autoItem(GENRE_PREFIX, "Genre", genreArt(it.title)) }
            // Skip the recent play that IS the current session: Continue listening
            // already offers it (resumed), so listing it here too (restarted from
            // track one) is the same thing twice with a subtle difference nobody
            // wants to think about at the wheel.
            RECENT_ID -> {
                val current = playback.sessionTrackIds()
                recentPlays.recentPlays.first()
                    .filterNot { current.isNotEmpty() && it.trackIds == current }
                    .map { it.autoItem() }
            }
            MIXES_ID -> mixTiles(coordinator.mixes())
            ALBUMS_ID -> library.albums(musicLibraryId()).map { it.autoItem(ALBUM_PREFIX, "Album") }
            ARTISTS_ID -> library.artists(musicLibraryId()).map { it.autoItem(ARTIST_PREFIX, "Artist") }
            // Playlists span libraries, so unlike albums/artists there's no library id to scope by.
            PLAYLISTS_ID -> library.playlists().map { it.autoItem(PLAYLIST_PREFIX, "Playlist") }
            AUDIOBOOK_RESUME_ID -> library.resumeAudiobooks(audiobookLibraryId(), AUTO_RESUME_LIMIT)
                .map { it.autoItem(AUDIOBOOK_RESUME_PREFIX, "Resume") }
            AUDIOBOOK_BOOKS_ID -> library.books(parentId = audiobookLibraryId())
                .map { it.autoItem(BOOK_PREFIX, "Book") }
            AUDIOBOOK_AUTHORS_ID -> library.authors(audiobookLibraryId())
                .map { it.autoItem(AUTHOR_PREFIX, "Author") }
            else -> emptyList()
        }

    private suspend fun playAutoItem(mediaId: String): Boolean {
        when {
            mediaId == CONTINUE_ID -> return playback.continueListening()
            mediaId.startsWith(RECENT_PREFIX) -> {
                val key = Uri.decode(mediaId.removePrefix(RECENT_PREFIX))
                val recent = recentPlays.recentPlays.first().firstOrNull { it.key == key } ?: return false
                val items = library.itemsByIds(recent.trackIds)
                val first = items.firstOrNull() ?: return false
                playback.playQueue(items, first, PlaybackSource(recent.key, recent.title, recent.subtitle, recent.coverUrl))
                return true
            }
            mediaId.startsWith(MIX_PREFIX) -> {
                val mixId = Uri.decode(mediaId.removePrefix(MIX_PREFIX))
                val detail = coordinator.mixDetail(mixId)
                val items = library.itemsByIds(detail.tracks.map { it.id })
                val first = items.firstOrNull() ?: return false
                playback.playQueue(items, first, PlaybackSource("mix:$mixId", detail.mix.displayTitle(), "Sonic mix", first.imageUrl))
                return true
            }
            mediaId.startsWith(ALBUM_PREFIX) -> {
                val albumId = Uri.decode(mediaId.removePrefix(ALBUM_PREFIX))
                val items = library.playableItems(albumId, DetailKind.ALBUM_TRACKS)
                val first = items.firstOrNull() ?: return false
                playback.playQueue(items, first, PlaybackSource("album:$albumId", first.album ?: "Album", "Album", first.imageUrl))
                return true
            }
            mediaId.startsWith(ARTIST_PREFIX) -> {
                val artistId = Uri.decode(mediaId.removePrefix(ARTIST_PREFIX))
                val items = library.playableItems(artistId, DetailKind.ARTIST_ALBUMS)
                val first = items.firstOrNull() ?: return false
                playback.playQueue(items, first, PlaybackSource("artist:$artistId", first.subtitle ?: "Artist", "Artist", first.imageUrl))
                return true
            }
            mediaId == STATION_LIBRARY_ID ->
                return playStationQueue(library.libraryRadio(musicLibraryId()), STATION_LIBRARY_ID, "Library Radio")
            mediaId == STATION_RANDOM_ALBUM_ID ->
                return playStationQueue(library.randomAlbumRadio(musicLibraryId()), STATION_RANDOM_ALBUM_ID, "Random Album Radio")
            mediaId.startsWith(DECADE_PREFIX) -> {
                val decade = mediaId.removePrefix(DECADE_PREFIX).toIntOrNull() ?: return false
                return playStationQueue(library.decadeRadio(musicLibraryId(), decade), mediaId, "${decade}s")
            }
            mediaId.startsWith(GENRE_PREFIX) -> {
                val genreId = Uri.decode(mediaId.removePrefix(GENRE_PREFIX))
                val items = library.playableItems(genreId, DetailKind.GENRE_TRACKS)
                val first = items.firstOrNull() ?: return false
                // Genre tracks don't carry the genre's name, so look it up for the label.
                val name = runCatching { library.genres(musicLibraryId()).firstOrNull { it.id == genreId }?.title }
                    .getOrNull() ?: "Genre"
                playback.playQueue(items, first, PlaybackSource("genre:$name", name, "Genre", first.imageUrl))
                return true
            }
            mediaId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = Uri.decode(mediaId.removePrefix(PLAYLIST_PREFIX))
                val items = library.playableItems(playlistId, DetailKind.PLAYLIST_TRACKS)
                val first = items.firstOrNull() ?: return false
                // A playlist's tracks don't carry its name (unlike album tracks), so
                // look it up for the "playing from" label.
                val name = runCatching { library.playlists().firstOrNull { it.id == playlistId }?.title }
                    .getOrNull() ?: "Playlist"
                playback.playQueue(items, first, PlaybackSource("playlist:$playlistId", name, "Playlist", first.imageUrl))
                return true
            }
            mediaId.startsWith(AUDIOBOOK_RESUME_PREFIX) -> {
                val bookId = Uri.decode(mediaId.removePrefix(AUDIOBOOK_RESUME_PREFIX))
                return playAudiobookBook(bookId)
            }
            mediaId.startsWith(BOOK_PREFIX) -> {
                val bookId = Uri.decode(mediaId.removePrefix(BOOK_PREFIX))
                return playAudiobookBook(bookId)
            }
            mediaId.startsWith(AUTHOR_PREFIX) -> {
                val authorId = Uri.decode(mediaId.removePrefix(AUTHOR_PREFIX))
                val items = library.playableItems(authorId, DetailKind.AUTHOR_BOOKS)
                val first = items.resumeStartItem() ?: items.firstOrNull() ?: return false
                playback.playQueue(items, first, PlaybackSource("author:$authorId", first.subtitle ?: "Author", "Audiobook author", first.imageUrl))
                return true
            }
        }
        return false
    }

    /** Stations are generated queues, so the source label is the station, not a track. */
    private fun playStationQueue(items: List<LibraryItem>, key: String, label: String): Boolean {
        val first = items.firstOrNull() ?: return false
        playback.playQueue(items, first, PlaybackSource("station:$key", label, "Station", first.imageUrl))
        return true
    }

    private suspend fun playAudiobookBook(bookId: String): Boolean {
        val items = library.playableItems(bookId, DetailKind.BOOK_CHAPTERS)
        val first = items.resumeStartItem() ?: items.firstOrNull() ?: return false
        playback.playQueue(items, first, PlaybackSource("book:$bookId", first.album ?: first.title, "Audiobook", first.imageUrl))
        return true
    }

    private suspend fun musicLibraryId(): String =
        library.audioLibraries().preferredLibrary(
            LibraryKind.MUSIC,
            settings.selectedMusicLibraryId.first(),
        )?.id
            ?: throw IllegalStateException("No music library found")

    private suspend fun audiobookLibraryId(): String =
        library.audioLibraries().preferredLibrary(
            LibraryKind.AUDIOBOOKS,
            settings.selectedAudiobookLibraryId.first(),
        )?.id
            ?: throw IllegalStateException("No audiobook library found")

    private suspend fun mediaItemForId(mediaId: String): MediaItem? =
        when (mediaId) {
            ROOT_ID -> rootItem()
            HOME_ID -> browsableItem(HOME_ID, "Home", R.drawable.ic_auto_home)
            LIBRARY_ID -> browsableItem(LIBRARY_ID, "Library", R.drawable.ic_auto_library)
            CONTINUE_ID -> playback.continueListeningPreview()?.let { continueItem(it) }
            RECENT_ID -> browsableItem(RECENT_ID, "Recent plays", R.drawable.auto_art_see_recent)
            MIXES_ID -> browsableItem(MIXES_ID, "Sonic Mixes", R.drawable.auto_art_see_mixes)
            ALBUMS_ID -> browsableItem(ALBUMS_ID, "Albums", R.drawable.auto_art_lib_albums)
            ARTISTS_ID -> browsableItem(ARTISTS_ID, "Artists", R.drawable.auto_art_lib_artists)
            PLAYLISTS_ID -> browsableItem(PLAYLISTS_ID, "Playlists", R.drawable.auto_art_lib_playlists)
            STATIONS_ID -> browsableItem(STATIONS_ID, "Stations", R.drawable.ic_auto_stations)
            DECADES_ID -> browsableItem(DECADES_ID, "Decade Radio", R.drawable.auto_art_station_decades)
            GENRES_ID -> browsableItem(GENRES_ID, "Genres", R.drawable.auto_art_station_genres)
            STATION_LIBRARY_ID -> stationItem(STATION_LIBRARY_ID, "Library Radio")
            STATION_RANDOM_ALBUM_ID -> stationItem(STATION_RANDOM_ALBUM_ID, "Random Album Radio")
            AUDIOBOOKS_ID -> browsableItem(AUDIOBOOKS_ID, "Audiobooks", R.drawable.ic_auto_audiobooks)
            AUDIOBOOK_RESUME_ID -> browsableItem(AUDIOBOOK_RESUME_ID, "Resume audiobooks", R.drawable.auto_art_ab_resume)
            AUDIOBOOK_BOOKS_ID -> browsableItem(AUDIOBOOK_BOOKS_ID, "All books", R.drawable.auto_art_ab_books)
            AUDIOBOOK_AUTHORS_ID -> browsableItem(AUDIOBOOK_AUTHORS_ID, "Authors", R.drawable.auto_art_ab_authors)
            else -> playableMediaItemForId(mediaId)
        }

    private suspend fun playableMediaItemForId(mediaId: String): MediaItem? =
        when {
            mediaId.startsWith(RECENT_PREFIX) -> {
                val key = Uri.decode(mediaId.removePrefix(RECENT_PREFIX))
                recentPlays.recentPlays.first().firstOrNull { it.key == key }?.autoItem()
            }
            mediaId.startsWith(MIX_PREFIX) -> {
                val mixId = Uri.decode(mediaId.removePrefix(MIX_PREFIX))
                runCatching { coordinator.mixDetail(mixId).mix.autoItem() }.getOrNull()
            }
            mediaId.startsWith(ALBUM_PREFIX) -> {
                val albumId = Uri.decode(mediaId.removePrefix(ALBUM_PREFIX))
                library.albums(musicLibraryId()).firstOrNull { it.id == albumId }?.autoItem(ALBUM_PREFIX, "Album")
            }
            mediaId.startsWith(ARTIST_PREFIX) -> {
                val artistId = Uri.decode(mediaId.removePrefix(ARTIST_PREFIX))
                library.artists(musicLibraryId()).firstOrNull { it.id == artistId }?.autoItem(ARTIST_PREFIX, "Artist")
            }
            mediaId.startsWith(DECADE_PREFIX) -> {
                val decade = mediaId.removePrefix(DECADE_PREFIX).toIntOrNull()
                decade?.takeIf { it in STATION_DECADES }?.let { stationItem(mediaId, "${it}s") }
            }
            mediaId.startsWith(GENRE_PREFIX) -> {
                val genreId = Uri.decode(mediaId.removePrefix(GENRE_PREFIX))
                library.genres(musicLibraryId()).firstOrNull { it.id == genreId }?.autoItem(GENRE_PREFIX, "Genre")
            }
            mediaId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = Uri.decode(mediaId.removePrefix(PLAYLIST_PREFIX))
                library.playlists().firstOrNull { it.id == playlistId }?.autoItem(PLAYLIST_PREFIX, "Playlist")
            }
            mediaId.startsWith(AUDIOBOOK_RESUME_PREFIX) -> {
                val bookId = Uri.decode(mediaId.removePrefix(AUDIOBOOK_RESUME_PREFIX))
                library.resumeAudiobooks(audiobookLibraryId(), AUTO_RESUME_LIMIT)
                    .firstOrNull { it.id == bookId }
                    ?.autoItem(AUDIOBOOK_RESUME_PREFIX, "Resume")
            }
            mediaId.startsWith(BOOK_PREFIX) -> {
                val bookId = Uri.decode(mediaId.removePrefix(BOOK_PREFIX))
                library.books(parentId = audiobookLibraryId()).firstOrNull { it.id == bookId }?.autoItem(BOOK_PREFIX, "Book")
            }
            mediaId.startsWith(AUTHOR_PREFIX) -> {
                val authorId = Uri.decode(mediaId.removePrefix(AUTHOR_PREFIX))
                library.authors(audiobookLibraryId()).firstOrNull { it.id == authorId }?.autoItem(AUTHOR_PREFIX, "Author")
            }
            else -> null
        }

    /**
     * Home is a grid of artwork tiles, the Spotify / YouTube Music shape: what you
     * were just listening to first, then the mixes. The grid style lives on the
     * HOME item itself ([childStyleFor]) because Auto takes tile-vs-list from the
     * PARENT node, never from the children.
     *
     * Continue listening shares ONE group ("Jump back in") with the newest Recent
     * plays and their "See all" tile. As a group of its own it was a single tile
     * with an empty row beside it, pushing everything else off the first screen.
     * Sonic Mixes follow as their own group. Stations have their own tab.
     *
     * An earlier cut did Recent/Mixes as headed LIST groups and ran to three
     * scrolls with the same mix three times over, so two things differ now: tiles
     * are compact, and a recent play that IS a mix is left out of the Recent tiles
     * (the Mixes group already shows it).
     */
    private suspend fun homeChildren(): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        playback.continueListeningPreview()?.let { out += continueItem(it) }

        val current = playback.sessionTrackIds()
        val recents = recentPlays.recentPlays.first()
            .filterNot { current.isNotEmpty() && it.trackIds == current }
            .filterNot { it.key.startsWith("mix:") }
            .take(HOME_RECENTS)
        out += recents.map { it.autoItem().styled(group = JUMP_BACK_IN) }
        if (recents.isNotEmpty()) {
            out += browsableItem(RECENT_ID, "See all", R.drawable.auto_art_see_recent).styled(group = JUMP_BACK_IN)
        }

        val mixes = runCatching { mixTiles(coordinator.mixes().take(HOME_TILES)) }.getOrDefault(emptyList())
        if (mixes.isNotEmpty()) {
            out += mixes.map { it.styled(group = "Sonic Mixes") }
            out += browsableItem(MIXES_ID, "See all", R.drawable.auto_art_see_mixes).styled(group = "Sonic Mixes")
        }

        return out
    }

    /** The Stations tab: the radios, then every decade as its own one-tap tile. */
    private fun stationsChildren(): List<MediaItem> =
        listOf(
            stationItem(STATION_LIBRARY_ID, "Library Radio").styled(group = "Radio"),
            stationItem(STATION_RANDOM_ALBUM_ID, "Random Album Radio").styled(group = "Radio"),
            browsableItem(GENRES_ID, "Genres", R.drawable.auto_art_station_genres).styled(group = "Radio"),
        ) + STATION_DECADES.map { stationItem(DECADE_PREFIX + it, "${it}s").styled(group = "Decades") }

    /**
     * The Audiobooks tab: the books in progress straight away as big cover tiles
     * (this is the tab that matters most to a listener who just wants to carry on),
     * then two tiles into the whole library.
     *
     * Continue listening is held to ONE ROW ([AUDIOBOOK_ROW] tiles) so Books and
     * Authors stay near the top. Most listeners have one or two books going; with
     * more than a row's worth, the last slot becomes "See all" instead.
     */
    private suspend fun audiobooksChildren(): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val resumes = runCatching { library.resumeAudiobooks(audiobookLibraryId(), AUDIOBOOK_ROW + 1) }
            .getOrDefault(emptyList())
        if (resumes.isNotEmpty()) {
            val overflow = resumes.size > AUDIOBOOK_ROW
            out += resumes.take(if (overflow) AUDIOBOOK_ROW - 1 else AUDIOBOOK_ROW)
                .map { it.autoItem(AUDIOBOOK_RESUME_PREFIX, "Resume").styled(group = "Continue listening") }
            if (overflow) {
                out += browsableItem(AUDIOBOOK_RESUME_ID, "See all", R.drawable.auto_art_ab_resume)
                    .styled(group = "Continue listening")
            }
        }
        out += browsableItem(AUDIOBOOK_BOOKS_ID, "All books", R.drawable.auto_art_ab_books).styled(group = "Browse")
        out += browsableItem(AUDIOBOOK_AUTHORS_ID, "Authors", R.drawable.auto_art_ab_authors).styled(group = "Browse")
        return out
    }

    /** Mix tiles with their cover art. A failed artwork lookup just leaves the tiles bare. */
    private suspend fun mixTiles(mixes: List<SonicMixDto>): List<MediaItem> {
        val art = runCatching { library.artworkByIds(mixes.mapNotNull { it.coverTrackId }) }
            .getOrDefault(emptyMap())
        return mixes.map { it.autoItem(artworkUrl = it.coverTrackId?.let { id -> art[id] }) }
    }

    private fun continueItem(preview: ContinueListening): MediaItem =
        playableItem(
            mediaId = CONTINUE_ID,
            title = preview.title,
            subtitle = preview.subtitle,
            artworkUrl = preview.artworkUrl,
        ).styled(group = JUMP_BACK_IN)

    /**
     * Put an item under a heading. Items sharing a [group] must be contiguous or
     * Auto starts a second group. Tile-vs-list is NOT set here: Auto takes that
     * from the PARENT node, so it is set on the container by [childStyleFor].
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun MediaItem.styled(group: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
            putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    /**
     * How a container's CHILDREN are drawn. Grids put the artwork first, which is
     * what makes a music app look like one; lists are kept where there is no
     * artwork to show (Artists, Audiobook authors) or the rows are text.
     * The CONTENT_STYLE constants are @UnstableApi: opted in here, on [styled] and
     * on [rootItem], the places that knowingly use them, so a Media3 upgrade has a
     * marker to re-check.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun childStyleFor(mediaId: String): Int? =
        when (mediaId) {
            HOME_ID, STATIONS_ID, LIBRARY_ID, ALBUMS_ID, PLAYLISTS_ID, RECENT_ID, MIXES_ID, DECADES_ID, GENRES_ID,
            AUDIOBOOKS_ID, AUDIOBOOK_RESUME_ID, AUDIOBOOK_BOOKS_ID, AUDIOBOOK_AUTHORS_ID ->
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            else -> null
        }

    /**
     * The root, flagged as content-style aware so Auto honours the per-item hints
     * above. The flag key predates Media3 and has no constant there, hence the
     * literal — it's the documented Android Auto string.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun rootItem(): MediaItem {
        val extras = Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("liquidWave")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MIXED)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun RecentPlay.autoItem(): MediaItem =
        playableItem(
            mediaId = RECENT_PREFIX + Uri.encode(key),
            title = title,
            subtitle = subtitle,
            artworkUrl = coverUrl,
        )

    // Raw name, NOT displayTitle(): the phone strips the coordinator's "(2)"
    // suffix for tidiness, but in Auto two mixes both called "Driving Mid-Tempo"
    // with different artwork read as a bug. The suffix is what tells them apart.
    private fun SonicMixDto.autoItem(artworkUrl: String? = null): MediaItem =
        playableItem(
            mediaId = MIX_PREFIX + Uri.encode(id),
            title = name?.takeIf { it.isNotBlank() } ?: "Sonic mix",
            subtitle = "Sonic mix",
            artworkUrl = artworkUrl,
        )

    private fun LibraryItem.autoItem(prefix: String, subtitleFallback: String, fallbackArt: Int? = null): MediaItem =
        playableItem(
            mediaId = prefix + Uri.encode(id),
            title = title,
            subtitle = subtitle ?: subtitleFallback,
            artworkUrl = imageUrl?.takeIf { it.isNotBlank() } ?: fallbackArt?.let(::resUri),
        )

    private fun SonicMixDto.displayTitle(): String =
        (name?.takeIf { it.isNotBlank() } ?: "Sonic mix").replace(""" \(\d+\)$""".toRegex(), "")

    /** A station tile: playable on one tap. Stations have no cover, so each gets generated art. */
    private fun stationItem(mediaId: String, title: String): MediaItem =
        playableItem(mediaId = mediaId, title = title, subtitle = "Station", artworkUrl = resUri(stationArt(mediaId)))

    private fun resUri(res: Int): String = "android.resource://$packageName/$res"

    private fun stationArt(mediaId: String): Int =
        when {
            mediaId == STATION_LIBRARY_ID -> R.drawable.auto_art_station_library
            mediaId == STATION_RANDOM_ALBUM_ID -> R.drawable.auto_art_station_random
            mediaId.startsWith(DECADE_PREFIX) -> when (mediaId.removePrefix(DECADE_PREFIX).toIntOrNull()?.rem(100)) {
                60 -> R.drawable.auto_art_decade_60
                70 -> R.drawable.auto_art_decade_70
                80 -> R.drawable.auto_art_decade_80
                90 -> R.drawable.auto_art_decade_90
                0 -> R.drawable.auto_art_decade_00
                10 -> R.drawable.auto_art_decade_10
                20 -> R.drawable.auto_art_decade_20
                else -> R.drawable.auto_art_station_decades
            }
            else -> R.drawable.auto_art_station_library
        }

    /** A stable gradient per genre name, so a genre keeps its colour between visits. */
    private fun genreArt(name: String): Int {
        val tiles = intArrayOf(
            R.drawable.auto_art_genre_0, R.drawable.auto_art_genre_1, R.drawable.auto_art_genre_2,
            R.drawable.auto_art_genre_3, R.drawable.auto_art_genre_4, R.drawable.auto_art_genre_5,
            R.drawable.auto_art_genre_6, R.drawable.auto_art_genre_7,
        )
        return tiles[Math.floorMod(name.lowercase().hashCode(), tiles.size)]
    }

    /**
     * [iconRes] becomes the tab glyph when the item is a root child: Auto reads a
     * tab's icon off its artwork URI, and a resource URI is the documented way
     * to hand it a drawable. Resolved through the real package name, because the
     * debug build's applicationId carries a suffix and a hardcoded package would
     * silently give the debug build no icons.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun browsableItem(mediaId: String, title: String, iconRes: Int? = null): MediaItem {
        val style = childStyleFor(mediaId)
        val extras = style?.let {
            Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it)
            }
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MIXED)
                    .setArtworkUri(iconRes?.let { Uri.parse(resUri(it)) })
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun playableItem(
        mediaId: String,
        title: String,
        subtitle: String?,
        artworkUrl: String?,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artworkUrl?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()

    private fun currentPlayerItems(): List<MediaItem> =
        playback.activePlayerSnapshot().let { player ->
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        }

    private fun <T> futureValue(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        serviceScope.launch {
            runCatching { block() }.fold(
                onSuccess = { future.set(it) },
                onFailure = { future.setException(it) },
            )
        }
        return future
    }

    private fun <T> futureResult(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> =
        futureValue(block)

    private fun <T> List<T>.paged(page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return this
        val from = page * pageSize
        if (from >= size) return emptyList()
        return subList(from, minOf(from + pageSize, size))
    }

    private companion object {
        /** How many Mixes tiles Home shows before its "See all" tile (two rows of three with it). */
        const val HOME_TILES = 5

        /**
         * Recent-play tiles beside Continue listening: Continue + 4 + "See all" is two
         * full rows of three, with no orphan tile left on a row of its own.
         */
        const val HOME_RECENTS = 4
        const val JUMP_BACK_IN = "Jump back in"

        /**
         * Tiles that fit across one row of Auto's grid at the screen size tested (the
         * DHU's wide layout shows three). A wider head unit just leaves a gap.
         */
        const val AUDIOBOOK_ROW = 3
        const val ROOT_ID = "auto:root"
        const val HOME_ID = "auto:home"
        const val LIBRARY_ID = "auto:library"
        const val CONTINUE_ID = "auto:continue"
        const val RECENT_ID = "auto:recent"
        const val MIXES_ID = "auto:mixes"
        const val ALBUMS_ID = "auto:albums"
        const val ARTISTS_ID = "auto:artists"
        const val PLAYLISTS_ID = "auto:playlists"
        const val STATIONS_ID = "auto:stations"
        const val DECADES_ID = "auto:stations:decades"
        const val GENRES_ID = "auto:stations:genres"
        const val STATION_LIBRARY_ID = "auto:station:library"
        const val STATION_RANDOM_ALBUM_ID = "auto:station:randomalbum"
        const val AUDIOBOOKS_ID = "auto:audiobooks"
        const val AUDIOBOOK_RESUME_ID = "auto:audiobooks:resume"
        const val AUDIOBOOK_BOOKS_ID = "auto:audiobooks:books"
        const val AUDIOBOOK_AUTHORS_ID = "auto:audiobooks:authors"
        const val RECENT_PREFIX = "auto:recent:"
        const val MIX_PREFIX = "auto:mix:"
        const val ALBUM_PREFIX = "auto:album:"
        const val ARTIST_PREFIX = "auto:artist:"
        const val PLAYLIST_PREFIX = "auto:playlist:"
        const val DECADE_PREFIX = "auto:decade:"
        const val GENRE_PREFIX = "auto:genre:"
        const val AUDIOBOOK_RESUME_PREFIX = "auto:audiobook:resume:"
        const val BOOK_PREFIX = "auto:book:"
        const val AUTHOR_PREFIX = "auto:author:"
        const val AUTO_RESUME_LIMIT = 50
        const val ACTION_SHUFFLE = "guru.liquid.embysonic.SHUFFLE"
        const val ACTION_REPEAT = "guru.liquid.embysonic.REPEAT"
        const val TAG = "SonicPlaybackService"

        // How long to give Media3 to post its own media notification before the
        // watchdog steps in. Android's window is ~30s, so this is conservative
        // while still leaving plenty of margin.
        const val FOREGROUND_WATCHDOG_DELAY_MS = 5_000L

        /**
         * How often to re-check that a cast still has a foreground notification.
         * Frequent enough that a gap can't last long — the freeze only bites after
         * a sustained idle period — but idle enough to cost nothing over an hour.
         */
        const val CAST_FOREGROUND_CHECK_MS = 15_000L

        // Mirrors Media3's DefaultMediaNotificationProvider defaults, so the real
        // media notification REPLACES the watchdog's placeholder rather than
        // sitting beside it. (Not referenced via the Media3 constants directly:
        // DefaultMediaNotificationProvider is @UnstableApi, and the values are
        // stable across the 1.x line we pin.)
        const val MEDIA_NOTIFICATION_ID = 1001
        const val MEDIA_CHANNEL_ID = "default_channel_id"
    }
}
