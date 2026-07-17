package guru.liquid.embysonic.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDto
import guru.liquid.embysonic.data.coordinator.toLibraryItem
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.resumeStartItem
import guru.liquid.embysonic.data.recent.RecentPlay
import guru.liquid.embysonic.data.recent.RecentPlaysRepository
import guru.liquid.embysonic.MainActivity
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
            .build()
        serviceScope.launch {
            playback.activePlayer.collect { active ->
                val next = sessionPlayerFor(active)
                sessionPlayer = next
                mediaSession?.setPlayer(next)
            }
        }
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
                browsableItem(RECENT_ID, "Recent plays"),
                browsableItem(MIXES_ID, "Sonic Mixes"),
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
        const val AUDIOBOOKS_ID = "auto:audiobooks"
        const val AUDIOBOOK_RESUME_ID = "auto:audiobooks:resume"
        const val AUDIOBOOK_BOOKS_ID = "auto:audiobooks:books"
        const val AUDIOBOOK_AUTHORS_ID = "auto:audiobooks:authors"
        const val RECENT_PREFIX = "auto:recent:"
        const val MIX_PREFIX = "auto:mix:"
        const val ALBUM_PREFIX = "auto:album:"
        const val ARTIST_PREFIX = "auto:artist:"
        const val PLAYLIST_PREFIX = "auto:playlist:"
        const val AUDIOBOOK_RESUME_PREFIX = "auto:audiobook:resume:"
        const val BOOK_PREFIX = "auto:book:"
        const val AUTHOR_PREFIX = "auto:author:"
        const val AUTO_RESUME_LIMIT = 50
    }
}
