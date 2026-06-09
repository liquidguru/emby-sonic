package guru.liquid.embysonic.data.emby

import guru.liquid.embysonic.data.emby.dto.EmbyItemDto
import guru.liquid.embysonic.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** A browsable audio library. Audiobooks are kept distinct from music. */
enum class LibraryKind { MUSIC, AUDIOBOOKS }

/**
 * A drill-down detail screen. Music and audiobooks share the same two shapes —
 * a grid of collections (albums/books) and a leaf list (tracks/chapters) — so one
 * screen, parameterised by kind, serves both.
 */
enum class DetailKind {
    ARTIST_ALBUMS,
    ALBUM_TRACKS,
    AUTHOR_BOOKS,
    BOOK_CHAPTERS,
    PLAYLIST_TRACKS;

    /** Grid kinds drill further; leaf kinds (tracks/chapters) don't. */
    val isGrid: Boolean get() = this == ARTIST_ALBUMS || this == AUTHOR_BOOKS

    /** Audiobooks lack cover art, so their placeholders use a book icon. */
    val usesBookIcon: Boolean get() = this == AUTHOR_BOOKS || this == BOOK_CHAPTERS

    /** The leaf list a grid item opens into, or null if this is already a leaf. */
    val childKind: DetailKind?
        get() = when (this) {
            ARTIST_ALBUMS -> ALBUM_TRACKS
            AUTHOR_BOOKS -> BOOK_CHAPTERS
            ALBUM_TRACKS, BOOK_CHAPTERS, PLAYLIST_TRACKS -> null
        }
}

data class AudioLibrary(
    val id: String,
    val name: String,
    val kind: LibraryKind,
)

/** Flattened item for library list/grid UIs, with its Emby image URL resolved. */
data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val trailingText: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
)

/**
 * Browse fetch cap. Set high so the full sorted list loads in one query — the A-Z
 * index needs the complete alphabet (Music has thousands of artists/albums). Emby
 * returns these comfortably on a LAN; LazyGrid/Column virtualise the rendering.
 */
private const val BROWSE_LIMIT = 10000

private fun formatDuration(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Reads music library data from Emby and maps it to [LibraryItem]s. Results are
 * capped (see [EmbyApi] Limit); pagination is a later refinement.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val embyApi: EmbyApi,
    private val settings: SettingsRepository,
    private val imageUrls: EmbyImageUrls,
) {
    private fun userId(): String =
        settings.snapshot().userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Not signed in")

    /** The user's audio libraries (music + audiobooks), discovered at runtime. */
    suspend fun audioLibraries(): List<AudioLibrary> =
        embyApi.getViews(userId()).items.mapNotNull { v ->
            val kind = when (v.collectionType) {
                "music" -> LibraryKind.MUSIC
                "audiobooks" -> LibraryKind.AUDIOBOOKS
                else -> return@mapNotNull null
            }
            val id = v.id ?: return@mapNotNull null
            AudioLibrary(id = id, name = v.name.orEmpty(), kind = kind)
        }

    suspend fun artists(libraryId: String): List<LibraryItem> =
        embyApi.getAlbumArtists(userId(), parentId = libraryId, limit = BROWSE_LIMIT)
            .items.map { it.toCollectionItem() }

    /**
     * Audiobook authors. Like books, most authors have no image of their own, so we
     * resolve each author's cover from one of their chapters (keyed by album-artist id).
     */
    suspend fun authors(libraryId: String): List<LibraryItem> {
        val authors = embyApi.getAlbumArtists(userId(), parentId = libraryId, limit = BROWSE_LIMIT)
            .items.map { it.toCollectionItem() }
        val covers = audiobookAuthorCovers(libraryId)
        return authors.map { if (it.imageUrl == null) it.copy(imageUrl = covers[it.id]) else it }
    }

    /** Author id → a chapter's Primary image URL, for authors lacking their own image. */
    private suspend fun audiobookAuthorCovers(libraryId: String): Map<String, String> {
        val chapters = embyApi.getItems(
            userId = userId(),
            includeItemTypes = "Audio",
            parentId = libraryId,
            limit = 3000,
        ).items
        val covers = HashMap<String, String>()
        for (c in chapters) {
            val tag = c.imageTags["Primary"] ?: continue
            val url = imageUrls.primary(c.id.orEmpty(), tag) ?: continue
            for (artist in c.albumArtists) {
                val id = artist.id ?: continue
                covers.putIfAbsent(id, url)
            }
        }
        return covers
    }

    suspend fun albums(libraryId: String): List<LibraryItem> =
        embyApi.getItems(userId(), parentId = libraryId, includeItemTypes = "MusicAlbum", limit = BROWSE_LIMIT)
            .items.map { it.toCollectionItem() }

    suspend fun recentlyAddedAlbums(libraryId: String, limit: Int): List<LibraryItem> =
        embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "MusicAlbum",
            sortBy = "DateCreated",
            sortOrder = "Descending",
            limit = limit,
        ).items.map { it.toCollectionItem() }

    /**
     * Audiobook "books" (MusicAlbum items). Most books have no album-level cover —
     * the art is embedded on the child Audio item — so we resolve each book's cover
     * from a representative chapter in one extra query. Scope by [parentId] (the
     * library, for the Books tab) or [albumArtistId] (an author, for the detail).
     */
    suspend fun books(parentId: String? = null, albumArtistId: String? = null): List<LibraryItem> {
        val books = embyApi.getItems(
            userId = userId(),
            includeItemTypes = "MusicAlbum",
            parentId = parentId,
            albumArtistIds = albumArtistId,
            limit = BROWSE_LIMIT,
        ).items.map { it.toCollectionItem() }
        val covers = audiobookCovers(parentId, albumArtistId)
        return books.map { if (it.imageUrl == null) it.copy(imageUrl = covers[it.id]) else it }
    }

    /** AlbumId → a child chapter's Primary image URL, for books lacking their own cover. */
    private suspend fun audiobookCovers(
        parentId: String?,
        albumArtistId: String?,
    ): Map<String, String> {
        val chapters = embyApi.getItems(
            userId = userId(),
            includeItemTypes = "Audio",
            parentId = parentId,
            albumArtistIds = albumArtistId,
            limit = 3000,
        ).items
        val covers = HashMap<String, String>()
        for (c in chapters) {
            val albumId = c.albumId ?: continue
            if (covers.containsKey(albumId)) continue
            val tag = c.imageTags["Primary"] ?: continue
            imageUrls.primary(c.id.orEmpty(), tag)?.let { covers[albumId] = it }
        }
        return covers
    }

    /**
     * The user's Emby playlists (across all libraries). Cover art is often absent —
     * only some playlists have a Primary image — so the placeholder shows otherwise.
     */
    suspend fun playlists(): List<LibraryItem> =
        embyApi.getItems(
            userId = userId(),
            includeItemTypes = "Playlist",
            fields = "ChildCount,PrimaryImageAspectRatio",
            limit = BROWSE_LIMIT,
        ).items.map { it.toPlaylistItem() }

    /** A playlist's tracks, in stored playlist order. */
    suspend fun playlistTracks(playlistId: String): List<LibraryItem> =
        embyApi.getPlaylistItems(playlistId, userId()).items.map { it.toTrackItem() }

    suspend fun tracks(libraryId: String): List<LibraryItem> =
        embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "Audio",
            sortBy = "Album,SortName",
        ).items.map { it.toTrackItem() }

    /**
     * Children of a drill-down. Artists/authors → their albums/books (a grid);
     * albums/books → their tracks/chapters in play order (a list). Audiobook
     * chapters and music tracks share the same Emby shape (Audio under a parent).
     */
    suspend fun childItems(parentId: String, kind: DetailKind): List<LibraryItem> =
        when (kind) {
            DetailKind.ARTIST_ALBUMS ->
                embyApi.getItems(
                    userId = userId(),
                    includeItemTypes = "MusicAlbum",
                    albumArtistIds = parentId,
                ).items.map { it.toCollectionItem() }

            // Books carry no album cover; resolve from a child chapter.
            DetailKind.AUTHOR_BOOKS -> books(albumArtistId = parentId)

            // Playlists keep their own stored order via the dedicated endpoint.
            DetailKind.PLAYLIST_TRACKS -> playlistTracks(parentId)

            DetailKind.ALBUM_TRACKS, DetailKind.BOOK_CHAPTERS ->
                embyApi.getItems(
                    userId = userId(),
                    parentId = parentId,
                    includeItemTypes = "Audio",
                    sortBy = "ParentIndexNumber,IndexNumber",
                ).items.map { it.toTrackItem() }
        }

    /** Tracks/chapters represented by a collection tile, suitable for direct playback. */
    suspend fun playableItems(collectionId: String, kind: DetailKind): List<LibraryItem> =
        when (kind) {
            DetailKind.ARTIST_ALBUMS, DetailKind.AUTHOR_BOOKS ->
                embyApi.getItems(
                    userId = userId(),
                    includeItemTypes = "Audio",
                    albumArtistIds = collectionId,
                    sortBy = "Album,ParentIndexNumber,IndexNumber,SortName",
                    limit = BROWSE_LIMIT,
                ).items.map { it.toTrackItem() }

            DetailKind.ALBUM_TRACKS, DetailKind.BOOK_CHAPTERS, DetailKind.PLAYLIST_TRACKS ->
                childItems(collectionId, kind)
        }

    /**
     * Artist or album cell: art comes from the item's own Primary image. The URL is
     * built ONLY when the item actually has a Primary tag — otherwise it stays null so
     * the UI shows its placeholder (a book icon for audiobooks, which have no covers).
     */
    private fun EmbyItemDto.toCollectionItem(): LibraryItem = LibraryItem(
        id = id.orEmpty(),
        title = name.orEmpty(),
        subtitle = when (type) {
            "MusicArtist" -> childCount?.let { "$it albums" }
            else -> albumArtist ?: artists.firstOrNull()
        },
        imageUrl = imageTags["Primary"]?.let { imageUrls.primary(id.orEmpty(), it) },
    )

    /** Playlist cell: own Primary art if present (often absent → placeholder); track count as subtitle. */
    private fun EmbyItemDto.toPlaylistItem(): LibraryItem = LibraryItem(
        id = id.orEmpty(),
        title = name.orEmpty(),
        subtitle = childCount?.let { "$it ${if (it == 1) "track" else "tracks"}" },
        imageUrl = imageTags["Primary"]?.let { imageUrls.primary(id.orEmpty(), it) },
    )

    /** Track row: art falls back to the parent album's Primary image, else null (placeholder). */
    private fun EmbyItemDto.toTrackItem(): LibraryItem {
        val art = when {
            imageTags["Primary"] != null -> imageUrls.primary(id.orEmpty(), imageTags["Primary"])
            albumId != null && albumPrimaryImageTag != null ->
                imageUrls.primary(albumId, albumPrimaryImageTag)
            else -> null
        }
        return LibraryItem(
            id = id.orEmpty(),
            title = name.orEmpty(),
            subtitle = artists.joinToString(", ").ifBlank { albumArtist },
            imageUrl = art,
            trailingText = formatDuration(durationMs),
            album = album,
            durationMs = durationMs,
        )
    }
}
