package guru.liquid.embysonic.data.emby

import guru.liquid.embysonic.data.emby.dto.EmbyItemDto
import guru.liquid.embysonic.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun artists(): List<LibraryItem> =
        embyApi.getAlbumArtists(userId()).items.map { it.toCollectionItem() }

    suspend fun albums(): List<LibraryItem> =
        embyApi.getItems(userId(), includeItemTypes = "MusicAlbum").items.map { it.toCollectionItem() }

    suspend fun tracks(): List<LibraryItem> =
        embyApi.getItems(
            userId = userId(),
            includeItemTypes = "Audio",
            sortBy = "Album,SortName",
        ).items.map { it.toTrackItem() }

    /** Artist or album cell: art comes from the item's own Primary image. */
    private fun EmbyItemDto.toCollectionItem(): LibraryItem = LibraryItem(
        id = id.orEmpty(),
        title = name.orEmpty(),
        subtitle = when (type) {
            "MusicArtist" -> childCount?.let { "$it albums" }
            else -> albumArtist ?: artists.firstOrNull()
        },
        imageUrl = imageUrls.primary(id.orEmpty(), imageTags["Primary"]),
    )

    /** Track row: art falls back to the parent album's Primary image. */
    private fun EmbyItemDto.toTrackItem(): LibraryItem {
        val art = when {
            imageTags.containsKey("Primary") -> imageUrls.primary(id.orEmpty(), imageTags["Primary"])
            albumId != null -> imageUrls.primary(albumId, albumPrimaryImageTag)
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
