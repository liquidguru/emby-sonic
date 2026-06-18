package guru.liquid.embysonic.data.playlist

import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.emby.EmbyApi
import guru.liquid.embysonic.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** High fetch cap so a collection's full track list is gathered in one query. */
private const val TRACK_LIMIT = 10000

/**
 * Builds music playlists and saves them to Emby (server-side, visible in every
 * Emby client). Track-id lists come from the sonic coordinator (similar / radio)
 * or from manually chosen albums/artists.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val embyApi: EmbyApi,
    private val coordinatorApi: CoordinatorApi,
    private val settings: SettingsRepository,
) {
    private fun userId(): String =
        settings.snapshot().userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Not signed in")

    /** Seed track + its sonically nearest neighbours. */
    suspend fun similarTrackIds(seedTrackId: String, n: Int = 25): List<String> =
        (listOf(seedTrackId) + coordinatorApi.similarTracks(seedTrackId, n).map { it.track.id })
            .distinct()

    /** A radio sequence seeded from the track, with the seed first. */
    suspend fun radioTrackIds(seedTrackId: String, length: Int = 25): List<String> {
        val radio = coordinatorApi.trackRadio(seedTrackId, length).tracks.map { it.id }
        return (listOf(seedTrackId) + radio).distinct()
    }

    /** All tracks belonging to the chosen albums (or artists), in play order. */
    suspend fun trackIdsForCollections(collectionIds: List<String>, areAlbums: Boolean): List<String> {
        val ids = ArrayList<String>()
        for (cid in collectionIds) {
            val result = if (areAlbums) {
                embyApi.getItems(
                    userId = userId(),
                    includeItemTypes = "Audio",
                    parentId = cid,
                    sortBy = "ParentIndexNumber,IndexNumber",
                    limit = TRACK_LIMIT,
                )
            } else {
                embyApi.getItems(
                    userId = userId(),
                    includeItemTypes = "Audio",
                    albumArtistIds = cid,
                    sortBy = "Album,ParentIndexNumber,IndexNumber",
                    limit = TRACK_LIMIT,
                )
            }
            result.items.forEach { it.id?.let(ids::add) }
        }
        return ids
    }

    /** Creates the Emby playlist; returns the number of tracks added. */
    suspend fun createPlaylist(name: String, trackIds: List<String>): Int {
        require(trackIds.isNotEmpty()) { "No tracks to add" }
        return embyApi.createPlaylist(
            name = name,
            ids = trackIds.joinToString(","),
            userId = userId(),
        ).itemAddedCount
    }

    /** Deletes an Emby playlist permanently from the server. */
    suspend fun deletePlaylist(itemId: String) {
        embyApi.deleteItem(itemId)
    }

    /** Removes one stored entry from an Emby playlist without deleting the underlying track. */
    suspend fun removePlaylistItem(playlistId: String, playlistItemId: String) {
        require(playlistItemId.isNotBlank()) { "Missing playlist entry id" }
        embyApi.deletePlaylistItems(playlistId = playlistId, entryIds = playlistItemId)
    }
}
