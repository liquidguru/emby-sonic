package guru.liquid.embysonic.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
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
import dagger.hilt.android.AndroidEntryPoint
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDto
import guru.liquid.embysonic.data.coordinator.toLibraryItem
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.STATION_DECADES
import guru.liquid.embysonic.data.emby.resumeStartItem
import guru.liquid.embysonic.data.recent.RecentPlay
import guru.liquid.embysonic.data.recent.RecentPlaysRepository
import guru.liquid.embysonic.MainActivity
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    private var mediaSession: MediaLibrarySession? = null
    private var sessionPlayer: AvrcpDurationPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        mediaSession?.release()
        mediaSession = null
        sessionPlayer = null
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
            Futures.immediateFuture(LibraryResult.ofItem(browsableItem(ROOT_ID, "liquidWave"), params))

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

    private fun sessionPlayerFor(player: Player): AvrcpDurationPlayer =
        AvrcpDurationPlayer(
            player = player,
            fallbackDurationMs = { playback.currentMetadataDurationMs() },
            positionMs = { playback.currentSessionPositionMs() },
            bufferedPositionMs = { playback.currentSessionBufferedPositionMs() },
            onSeekToMs = playback::seekTo,
        )

    private suspend fun childrenFor(parentId: String): List<MediaItem> =
        when (parentId) {
            ROOT_ID -> listOf(
                // Zero-effort "just play something" nodes first — they're what's
                // actually usable while driving.
                browsableItem(RECENT_ID, "Recent plays"),
                browsableItem(MIXES_ID, "Sonic Mixes"),
                browsableItem(STATIONS_ID, "Stations"),
                browsableItem(ALBUMS_ID, "Albums"),
                browsableItem(ARTISTS_ID, "Artists"),
                browsableItem(PLAYLISTS_ID, "Playlists"),
                browsableItem(AUDIOBOOKS_ID, "Audiobooks"),
            )
            AUDIOBOOKS_ID -> listOf(
                browsableItem(AUDIOBOOK_RESUME_ID, "Resume audiobooks"),
                browsableItem(AUDIOBOOK_BOOKS_ID, "Books"),
                browsableItem(AUDIOBOOK_AUTHORS_ID, "Authors"),
            )
            // Library/Random Album are playable (one tap = music). Decade and Genres
            // need a choice, so they browse one level deeper. Sonic Adventure and the
            // Artist Mix Creator are deliberately absent: both need a multi-item
            // selection that a browse tree can't express (and shouldn't, at speed).
            STATIONS_ID -> listOf(
                stationItem(STATION_LIBRARY_ID, "Library Radio"),
                stationItem(STATION_RANDOM_ALBUM_ID, "Random Album Radio"),
                browsableItem(DECADES_ID, "Decade Radio"),
                browsableItem(GENRES_ID, "Genres"),
            )
            DECADES_ID -> STATION_DECADES.map { stationItem(DECADE_PREFIX + it, "${it}s") }
            GENRES_ID -> library.genres(musicLibraryId()).map { it.autoItem(GENRE_PREFIX, "Genre") }
            RECENT_ID -> recentPlays.recentPlays.first().map { it.autoItem() }
            MIXES_ID -> coordinator.mixes().map { it.autoItem() }
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
                val items = detail.tracks.map { it.toLibraryItem() }.withArtwork()
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

    private suspend fun List<LibraryItem>.withArtwork(): List<LibraryItem> {
        val art = runCatching { library.artworkByIds(map { it.id }) }.getOrDefault(emptyMap())
        return map { item -> art[item.id]?.let { item.copy(imageUrl = it) } ?: item }
    }

    private suspend fun musicLibraryId(): String =
        library.audioLibraries().firstOrNull { it.kind == LibraryKind.MUSIC }?.id
            ?: throw IllegalStateException("No music library found")

    private suspend fun audiobookLibraryId(): String =
        library.audioLibraries().firstOrNull { it.kind == LibraryKind.AUDIOBOOKS }?.id
            ?: throw IllegalStateException("No audiobook library found")

    private suspend fun mediaItemForId(mediaId: String): MediaItem? =
        when (mediaId) {
            ROOT_ID -> browsableItem(ROOT_ID, "liquidWave")
            RECENT_ID -> browsableItem(RECENT_ID, "Recent plays")
            MIXES_ID -> browsableItem(MIXES_ID, "Sonic Mixes")
            ALBUMS_ID -> browsableItem(ALBUMS_ID, "Albums")
            ARTISTS_ID -> browsableItem(ARTISTS_ID, "Artists")
            PLAYLISTS_ID -> browsableItem(PLAYLISTS_ID, "Playlists")
            STATIONS_ID -> browsableItem(STATIONS_ID, "Stations")
            DECADES_ID -> browsableItem(DECADES_ID, "Decade Radio")
            GENRES_ID -> browsableItem(GENRES_ID, "Genres")
            STATION_LIBRARY_ID -> stationItem(STATION_LIBRARY_ID, "Library Radio")
            STATION_RANDOM_ALBUM_ID -> stationItem(STATION_RANDOM_ALBUM_ID, "Random Album Radio")
            AUDIOBOOKS_ID -> browsableItem(AUDIOBOOKS_ID, "Audiobooks")
            AUDIOBOOK_RESUME_ID -> browsableItem(AUDIOBOOK_RESUME_ID, "Resume audiobooks")
            AUDIOBOOK_BOOKS_ID -> browsableItem(AUDIOBOOK_BOOKS_ID, "Books")
            AUDIOBOOK_AUTHORS_ID -> browsableItem(AUDIOBOOK_AUTHORS_ID, "Authors")
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

    private fun RecentPlay.autoItem(): MediaItem =
        playableItem(
            mediaId = RECENT_PREFIX + Uri.encode(key),
            title = title,
            subtitle = subtitle,
            artworkUrl = coverUrl,
        )

    private fun SonicMixDto.autoItem(): MediaItem =
        playableItem(
            mediaId = MIX_PREFIX + Uri.encode(id),
            title = displayTitle(),
            subtitle = "Sonic mix",
            artworkUrl = null,
        )

    private fun LibraryItem.autoItem(prefix: String, subtitleFallback: String): MediaItem =
        playableItem(
            mediaId = prefix + Uri.encode(id),
            title = title,
            subtitle = subtitle ?: subtitleFallback,
            artworkUrl = imageUrl,
        )

    private fun SonicMixDto.displayTitle(): String =
        (name?.takeIf { it.isNotBlank() } ?: "Sonic mix").replace(""" \(\d+\)$""".toRegex(), "")

    /** A station tile: playable on one tap, with no artwork of its own. */
    private fun stationItem(mediaId: String, title: String): MediaItem =
        playableItem(mediaId = mediaId, title = title, subtitle = "Station", artworkUrl = null)

    private fun browsableItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                    .build(),
            )
            .build()

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
        const val ROOT_ID = "auto:root"
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
    }
}
