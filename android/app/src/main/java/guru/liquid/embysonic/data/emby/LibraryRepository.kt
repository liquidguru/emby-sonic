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
    BOOK_CHAPTERS;

    /** Grid kinds drill further; leaf kinds (tracks/chapters) don't. */
    val isGrid: Boolean get() = this == ARTIST_ALBUMS || this == AUTHOR_BOOKS

    /** Audiobooks lack cover art, so their placeholders use a book icon. */
    val usesBookIcon: Boolean get() = this == AUTHOR_BOOKS || this == BOOK_CHAPTERS

    /** The leaf list a grid item opens into, or null if this is already a leaf. */
    val childKind: DetailKind?
        get() = when (this) {
            ARTIST_ALBUMS -> ALBUM_TRACKS
            AUTHOR_BOOKS -> BOOK_CHAPTERS
            ALBUM_TRACKS, BOOK_CHAPTERS -> null
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
)

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
        embyApi.getAlbumArtists(userId(), parentId = libraryId).items.map { it.toCollectionItem() }

    suspend fun albums(libraryId: String): List<LibraryItem> =
        embyApi.getItems(userId(), parentId = libraryId, includeItemTypes = "MusicAlbum")
            .items.map { it.toCollectionItem() }

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
            DetailKind.ARTIST_ALBUMS, DetailKind.AUTHOR_BOOKS ->
                embyApi.getItems(
                    userId = userId(),
                    includeItemTypes = "MusicAlbum",
                    albumArtistIds = parentId,
                ).items.map { it.toCollectionItem() }

            DetailKind.ALBUM_TRACKS, DetailKind.BOOK_CHAPTERS ->
                embyApi.getItems(
                    userId = userId(),
                    parentId = parentId,
                    includeItemTypes = "Audio",
                    sortBy = "ParentIndexNumber,IndexNumber",
                ).items.map { it.toTrackItem() }
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
        )
    }
}
