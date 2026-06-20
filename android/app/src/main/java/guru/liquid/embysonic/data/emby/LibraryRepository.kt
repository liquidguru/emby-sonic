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
    GENRE_TRACKS,
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
            ALBUM_TRACKS, GENRE_TRACKS, BOOK_CHAPTERS, PLAYLIST_TRACKS -> null
        }

    /** Playback content kind for the tracks this drill-down produces. */
    fun contentKind(): ContentKind = when (this) {
        ARTIST_ALBUMS, ALBUM_TRACKS, GENRE_TRACKS -> ContentKind.MUSIC
        AUTHOR_BOOKS, BOOK_CHAPTERS -> ContentKind.AUDIOBOOK
        PLAYLIST_TRACKS -> ContentKind.UNKNOWN
    }
}

data class AudioLibrary(
    val id: String,
    val name: String,
    val kind: LibraryKind,
)

/** Flattened item for library list/grid UIs, with its Emby image URL resolved. */
/**
 * Whether a playable item is music or an audiobook/long-form, decided explicitly
 * from the source (library/endpoint) rather than inferred from duration. UNKNOWN
 * means the producer couldn't tell (e.g. a mixed playlist), and playback falls
 * back to the legacy duration heuristic. Drives resume, crossfade eligibility,
 * the stream endpoint, and Played-on-completion in PlaybackController.
 */
enum class ContentKind { MUSIC, AUDIOBOOK, UNKNOWN }

data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val trailingText: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val playbackPositionMs: Long = 0,
    val played: Boolean = false,
    val contentKind: ContentKind = ContentKind.UNKNOWN,
    val playlistItemId: String? = null,
)

/**
 * Where a book (or any ordered collection) should resume: the first chapter with
 * a mid-chapter position; otherwise — when the listener stopped on a chapter
 * boundary, where the finished chapter was marked Played with its position
 * cleared — the first unplayed chapter after the last played one. A fully
 * played or never-started book starts from the beginning.
 */
fun List<LibraryItem>.resumeStartItem(): LibraryItem? {
    val inProgress = firstOrNull { item ->
        val duration = item.durationMs
        item.playbackPositionMs > RESUME_MIN_POSITION_MS &&
            (duration == null || item.playbackPositionMs < duration - RESUME_END_PADDING_MS)
    }
    if (inProgress != null) return inProgress
    val lastPlayed = indexOfLast { it.played }
    if (lastPlayed in 0 until lastIndex) {
        subList(lastPlayed + 1, size).firstOrNull { !it.played }?.let { return it }
    }
    return firstOrNull()
}

private const val RESUME_MIN_POSITION_MS = 5_000L
private const val RESUME_END_PADDING_MS = 5_000L

/**
 * Browse fetch cap. Set high so the full sorted list loads in one query — the A-Z
 * index needs the complete alphabet (Music has thousands of artists/albums). Emby
 * returns these comfortably on a LAN; LazyGrid/Column virtualise the rendering.
 */
private const val BROWSE_LIMIT = 10000
private const val ALBUM_ART_SCAN_PAGE_SIZE = 1000

/** How many recently played tracks to scan when grouping recent plays to albums. */
private const val RECENT_PLAYS_SCAN = 100

/** Default length of a generated radio/station queue. */
private const val RADIO_QUEUE_LIMIT = 60

/** Max results for a track search. */
private const val SEARCH_LIMIT = 60
private const val GENRE_ID_SEPARATOR = "|"
private const val DEFAULT_GENRE_MIX_TRACKS = 50

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

    suspend fun artists(libraryId: String, limit: Int = BROWSE_LIMIT): List<LibraryItem> =
        embyApi.getAlbumArtists(userId(), parentId = libraryId, limit = limit)
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
            fields = "UserData,PrimaryImageAspectRatio",
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

    suspend fun albums(libraryId: String): List<LibraryItem> {
        val albums = embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "MusicAlbum",
            limit = BROWSE_LIMIT,
        ).items.map { it.toCollectionItem() }
        return hydrateMusicAlbumArt(albums, parentId = libraryId)
    }

    suspend fun genres(libraryId: String): List<LibraryItem> =
        embyApi.getGenres(
            userId = userId(),
            parentId = libraryId,
            limit = BROWSE_LIMIT,
        ).items.mapNotNull { it.toGenreItem(libraryId) }

    suspend fun recentlyAddedAlbums(libraryId: String, limit: Int): List<LibraryItem> {
        val albums = embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "MusicAlbum",
            sortBy = "DateCreated",
            sortOrder = "Descending",
            limit = limit,
        ).items.map { it.toCollectionItem() }
        return hydrateMusicAlbumArt(albums, parentId = libraryId)
    }

    /**
     * Recently played music, grouped back to albums (most-recent first). Emby's
     * `DatePlayed` lives on the track, so we scan a window of recently played
     * tracks and dedupe to their albums. Cover resolves from the album's Primary
     * image, falling back to the track's own.
     */
    suspend fun recentlyPlayedAlbums(libraryId: String, limit: Int): List<LibraryItem> {
        val tracks = embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "Audio",
            sortBy = "DatePlayed",
            sortOrder = "Descending",
            filters = "IsPlayed",
            limit = RECENT_PLAYS_SCAN,
        ).items
        val seen = HashSet<String>()
        val result = ArrayList<LibraryItem>()
        for (t in tracks) {
            val albumId = t.albumId ?: continue
            if (!seen.add(albumId)) continue
            val art = t.albumPrimaryImageTag?.let { imageUrls.primary(albumId, it) }
                ?: t.imageTags["Primary"]?.let { imageUrls.primary(t.id.orEmpty(), it) }
            result.add(
                LibraryItem(
                    id = albumId,
                    title = t.album ?: t.name.orEmpty(),
                    subtitle = t.albumArtist ?: t.artists.firstOrNull(),
                    imageUrl = art,
                ),
            )
            if (result.size >= limit) break
        }
        return result
    }

    suspend fun resumeAudiobooks(libraryId: String, limit: Int): List<LibraryItem> {
        val chapters = embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "Audio",
            sortBy = "DatePlayed",
            sortOrder = "Descending",
            fields = "UserData,PrimaryImageAspectRatio",
            limit = BROWSE_LIMIT,
        ).items
        val seenBooks = HashSet<String>()
        return chapters
            .asSequence()
            .filter { it.hasResumePosition() }
            .sortedByDescending { it.userData?.lastPlayedDate.orEmpty() }
            .mapNotNull { chapter ->
                val bookId = chapter.albumId ?: chapter.id ?: return@mapNotNull null
                if (!seenBooks.add(bookId)) return@mapNotNull null
                chapter.toResumeBookItem(bookId)
            }
            .take(limit)
            .toList()
    }

    /** Free-text track/chapter search ([parentId] scopes to a library). */
    suspend fun searchTracks(query: String, parentId: String? = null, limit: Int = SEARCH_LIMIT): List<LibraryItem> =
        searchItems(query, "Audio", parentId, limit) { it.toTrackItem(ContentKind.MUSIC) }

    /** Free-text album search ([parentId] scopes to the music library). */
    suspend fun searchAlbums(query: String, parentId: String? = null, limit: Int = SEARCH_LIMIT): List<LibraryItem> =
        searchItems(query, "MusicAlbum", parentId, limit) { it.toCollectionItem() }

    /** Free-text artist search ([parentId] scopes to the music library). */
    suspend fun searchArtists(query: String, parentId: String? = null, limit: Int = SEARCH_LIMIT): List<LibraryItem> =
        searchItems(query, "MusicArtist", parentId, limit) { it.toCollectionItem() }

    /** Free-text book search (audiobook MusicAlbums; [parentId] = audiobooks library). */
    suspend fun searchBooks(query: String, parentId: String? = null, limit: Int = SEARCH_LIMIT): List<LibraryItem> =
        searchItems(query, "MusicAlbum", parentId, limit) { it.toCollectionItem() }

    /** Free-text author search (audiobook MusicArtists; [parentId] = audiobooks library). */
    suspend fun searchAuthors(query: String, parentId: String? = null, limit: Int = SEARCH_LIMIT): List<LibraryItem> =
        searchItems(query, "MusicArtist", parentId, limit) { it.toCollectionItem() }

    private suspend fun searchItems(
        query: String,
        includeItemTypes: String,
        parentId: String?,
        limit: Int,
        map: (EmbyItemDto) -> LibraryItem,
    ): List<LibraryItem> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        return embyApi.getItems(
            userId = userId(),
            includeItemTypes = includeItemTypes,
            parentId = parentId,
            searchTerm = term,
            limit = limit,
        ).items.map(map)
    }

    /** Shuffle the whole music library into a radio queue. */
    suspend fun libraryRadio(libraryId: String, limit: Int = RADIO_QUEUE_LIMIT): List<LibraryItem> =
        embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "Audio",
            sortBy = "Random",
            limit = limit,
        ).items.map { it.toTrackItem(ContentKind.MUSIC) }

    /** Random tracks from a single decade ([decadeStart], e.g. 1990 → 1990–1999). */
    suspend fun decadeRadio(
        libraryId: String,
        decadeStart: Int,
        limit: Int = RADIO_QUEUE_LIMIT,
    ): List<LibraryItem> =
        embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "Audio",
            sortBy = "Random",
            limit = limit,
            years = (decadeStart until decadeStart + 10).joinToString(","),
        ).items.map { it.toTrackItem(ContentKind.MUSIC) }

    /** A handful of random albums, played start-to-finish in sequence. */
    suspend fun randomAlbumRadio(libraryId: String, albumCount: Int = 6): List<LibraryItem> {
        val albumIds = embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "MusicAlbum",
            sortBy = "Random",
            limit = albumCount,
        ).items.mapNotNull { it.id }
        val out = ArrayList<LibraryItem>()
        for (id in albumIds) {
            out += embyApi.getItems(
                userId = userId(),
                parentId = id,
                includeItemTypes = "Audio",
                sortBy = "ParentIndexNumber,IndexNumber",
                limit = 100,
            ).items.map { it.toTrackItem(ContentKind.MUSIC) }
        }
        return out
    }

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
    suspend fun playlists(limit: Int = BROWSE_LIMIT): List<LibraryItem> =
        embyApi.getItems(
            userId = userId(),
            includeItemTypes = "Playlist",
            fields = "ChildCount,UserData,PrimaryImageAspectRatio",
            limit = limit,
        ).items.map { it.toPlaylistItem() }

    /** A playlist's tracks, in stored playlist order. */
    suspend fun playlistTracks(playlistId: String): List<LibraryItem> =
        embyApi.getPlaylistItems(playlistId, userId()).items.map { it.toTrackItem(playlistItemId = it.playlistItemId) }

    suspend fun tracks(libraryId: String): List<LibraryItem> =
        embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "Audio",
            sortBy = "Album,SortName",
        ).items.map { it.toTrackItem(ContentKind.MUSIC) }

    /**
     * Children of a drill-down. Artists/authors → their albums/books (a grid);
     * albums/books → their tracks/chapters in play order (a list). Audiobook
     * chapters and music tracks share the same Emby shape (Audio under a parent).
     */
    suspend fun childItems(parentId: String, kind: DetailKind): List<LibraryItem> =
        when (kind) {
            DetailKind.ARTIST_ALBUMS -> {
                val albums = embyApi.getItems(
                    userId = userId(),
                    includeItemTypes = "MusicAlbum",
                    albumArtistIds = parentId,
                ).items.map { it.toCollectionItem() }
                hydrateMusicAlbumArt(albums, albumArtistId = parentId)
            }

            // Books carry no album cover; resolve from a child chapter.
            DetailKind.AUTHOR_BOOKS -> books(albumArtistId = parentId)

            // Playlists keep their own stored order via the dedicated endpoint.
            DetailKind.PLAYLIST_TRACKS -> playlistTracks(parentId)

            DetailKind.GENRE_TRACKS -> genreTracks(parentId)

            DetailKind.ALBUM_TRACKS, DetailKind.BOOK_CHAPTERS ->
                embyApi.getItems(
                    userId = userId(),
                    parentId = parentId,
                    includeItemTypes = "Audio",
                    sortBy = "ParentIndexNumber,IndexNumber",
                ).items.map { it.toTrackItem(kind.contentKind()) }
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
                ).items.map { it.toTrackItem(kind.contentKind()) }

            DetailKind.ALBUM_TRACKS, DetailKind.BOOK_CHAPTERS, DetailKind.PLAYLIST_TRACKS ->
                childItems(collectionId, kind)

            DetailKind.GENRE_TRACKS -> genreTracks(collectionId)
        }

    suspend fun genreTracks(
        packedGenreId: String,
        limit: Int = DEFAULT_GENRE_MIX_TRACKS,
    ): List<LibraryItem> {
        val (libraryId, genreId) = unpackGenreItemId(packedGenreId) ?: return emptyList()
        return embyApi.getItems(
            userId = userId(),
            parentId = libraryId,
            includeItemTypes = "Audio",
            genreIds = genreId,
            sortBy = "Random",
            limit = limit,
        ).items.map { it.toTrackItem(ContentKind.MUSIC) }
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

    /**
     * Music albums often lack their own Primary image even though their tracks
     * have embedded art. Fill those blanks from a representative child track.
     */
    private suspend fun hydrateMusicAlbumArt(
        albums: List<LibraryItem>,
        parentId: String? = null,
        albumArtistId: String? = null,
    ): List<LibraryItem> {
        val missing = albums.filter { it.imageUrl == null }.map { it.id }.toSet()
        if (missing.isEmpty()) return albums
        val covers = musicAlbumCovers(parentId, albumArtistId, missing)
        if (covers.isEmpty()) return albums
        return albums.map { album ->
            if (album.imageUrl == null) album.copy(imageUrl = covers[album.id]) else album
        }
    }

    private suspend fun musicAlbumCovers(
        parentId: String?,
        albumArtistId: String?,
        wantedAlbumIds: Set<String>,
    ): Map<String, String> {
        val covers = HashMap<String, String>()
        var startIndex = 0
        do {
            val page = embyApi.getItems(
                userId = userId(),
                includeItemTypes = "Audio",
                parentId = parentId,
                albumArtistIds = albumArtistId,
                sortBy = "Album,ParentIndexNumber,IndexNumber",
                startIndex = startIndex,
                limit = ALBUM_ART_SCAN_PAGE_SIZE,
            )
            for (track in page.items) {
                val albumId = track.albumId ?: continue
                if (albumId !in wantedAlbumIds || covers.containsKey(albumId)) continue
                track.artUrl()?.let { covers[albumId] = it }
                if (covers.size == wantedAlbumIds.size) break
            }
            startIndex += page.items.size
        } while (
            covers.size < wantedAlbumIds.size &&
                page.items.isNotEmpty() &&
                startIndex < page.totalRecordCount
        )
        return covers
    }

    private fun EmbyItemDto.toGenreItem(libraryId: String): LibraryItem? {
        val genreId = id?.takeIf { it.isNotBlank() } ?: return null
        val genreName = name?.takeIf { it.isNotBlank() } ?: return null
        return LibraryItem(
            id = packGenreItemId(libraryId, genreId),
            title = genreName,
            subtitle = "Genre",
            imageUrl = null,
            contentKind = ContentKind.MUSIC,
        )
    }

    /** Playlist cell: own Primary art if present (often absent → placeholder); track count as subtitle. */
    private fun EmbyItemDto.toPlaylistItem(): LibraryItem = LibraryItem(
        id = id.orEmpty(),
        title = name.orEmpty(),
        subtitle = childCount?.let { "$it ${if (it == 1) "track" else "tracks"}" },
        imageUrl = imageTags["Primary"]?.let { imageUrls.primary(id.orEmpty(), it) },
    )

    /**
     * Resolve item Primary art: its own tag first, else fall back to the parent
     * album's Primary image, else null (placeholder). Shared by track rows and
     * the batched [artworkByIds] hydration.
     */
    private fun EmbyItemDto.artUrl(): String? = when {
        imageTags["Primary"] != null -> imageUrls.primary(id.orEmpty(), imageTags["Primary"])
        albumId != null && albumPrimaryImageTag != null ->
            imageUrls.primary(albumId, albumPrimaryImageTag)
        else -> null
    }

    /**
     * Resolve Primary artwork URLs for a set of Emby item ids in one batched
     * query. Coordinator track lists (sonic mixes) carry no image metadata, so
     * mixes hydrate their covers through this. Returns id → url (url may be null
     * when the item has no resolvable cover).
     */
    suspend fun artworkByIds(ids: List<String>): Map<String, String?> {
        val wanted = ids.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val items = embyApi.getItemsByIds(userId(), wanted.joinToString(",")).items
        return items.associate { it.id.orEmpty() to it.artUrl() }
    }

    /**
     * Resolve playable music track items for a set of Emby ids, preserving the
     * requested order (Emby may return them in any order). Used to replay a
     * stored Recent plays queue. Ids no longer in the library are dropped.
     */
    suspend fun itemsByIds(ids: List<String>): List<LibraryItem> {
        val wanted = ids.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return emptyList()
        val byId = embyApi.getItemsByIds(userId(), wanted.distinct().joinToString(","))
            .items.associateBy { it.id.orEmpty() }
        return wanted.mapNotNull { byId[it]?.toTrackItem(ContentKind.MUSIC) }
    }

    /** Track row: art falls back to the parent album's Primary image, else null (placeholder). */
    private fun EmbyItemDto.toTrackItem(
        kind: ContentKind = ContentKind.UNKNOWN,
        playlistItemId: String? = null,
    ): LibraryItem {
        return LibraryItem(
            id = id.orEmpty(),
            title = name.orEmpty(),
            subtitle = artists.joinToString(", ").ifBlank { albumArtist },
            imageUrl = artUrl(),
            trailingText = formatDuration(durationMs),
            album = album,
            durationMs = durationMs,
            playbackPositionMs = userData?.playbackPositionTicks?.ticksToMs() ?: 0,
            played = userData?.played ?: false,
            contentKind = kind,
            playlistItemId = playlistItemId,
        )
    }

    private fun EmbyItemDto.toResumeBookItem(bookId: String): LibraryItem {
        val positionMs = userData?.playbackPositionTicks?.ticksToMs() ?: 0
        val art = imageTags["Primary"]?.let { imageUrls.primary(id.orEmpty(), it) }
            ?: albumPrimaryImageTag?.let { imageUrls.primary(bookId, it) }
        return LibraryItem(
            id = bookId,
            title = album ?: name.orEmpty(),
            subtitle = formatDuration(positionMs)?.let { "Resume at $it" },
            imageUrl = art,
            durationMs = durationMs,
            playbackPositionMs = positionMs,
            contentKind = ContentKind.AUDIOBOOK,
        )
    }

    private fun EmbyItemDto.hasResumePosition(): Boolean {
        val position = userData?.playbackPositionTicks?.ticksToMs() ?: return false
        val duration = durationMs
        return position > RESUME_MIN_POSITION_MS &&
            (duration == null || position < duration - RESUME_END_PADDING_MS)
    }

    private fun Long.ticksToMs(): Long = this / 10_000

    private fun packGenreItemId(libraryId: String, genreId: String): String =
        "$libraryId$GENRE_ID_SEPARATOR$genreId"

    private fun unpackGenreItemId(value: String): Pair<String, String>? {
        val separator = value.indexOf(GENRE_ID_SEPARATOR)
        if (separator <= 0 || separator == value.lastIndex) return null
        return value.substring(0, separator) to value.substring(separator + 1)
    }
}
