package guru.liquid.embysonic.data.emby.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmbyItemDto(
    @SerialName("Id")
    val id: String? = null,

    @SerialName("Name")
    val name: String? = null,

    @SerialName("ServerId")
    val serverId: String? = null,

    @SerialName("Type")
    val type: String? = null,

    @SerialName("CollectionType")
    val collectionType: String? = null,

    @SerialName("Album")
    val album: String? = null,

    @SerialName("AlbumId")
    val albumId: String? = null,

    @SerialName("AlbumArtist")
    val albumArtist: String? = null,

    @SerialName("Artists")
    val artists: List<String> = emptyList(),

    @SerialName("AlbumArtists")
    val albumArtists: List<NameIdPair> = emptyList(),

    @SerialName("ArtistItems")
    val artistItems: List<NameIdPair> = emptyList(),

    @SerialName("IndexNumber")
    val indexNumber: Int? = null,

    @SerialName("ParentIndexNumber")
    val parentIndexNumber: Int? = null,

    @SerialName("ProductionYear")
    val productionYear: Int? = null,

    @SerialName("RunTimeTicks")
    val runTimeTicks: Long? = null,

    // Source container (e.g. "mp3", "flac", "wma"). Used to tell whether Emby will
    // transcode a track — a transcoded track can't be crossfaded reliably.
    @SerialName("Container")
    val container: String? = null,

    @SerialName("ChildCount")
    val childCount: Int? = null,

    @SerialName("ImageTags")
    val imageTags: Map<String, String> = emptyMap(),

    @SerialName("AlbumPrimaryImageTag")
    val albumPrimaryImageTag: String? = null,

    @SerialName("BackdropImageTags")
    val backdropImageTags: List<String> = emptyList(),

    @SerialName("UserData")
    val userData: UserDataDto? = null,

    @SerialName("PlaylistItemId")
    val playlistItemId: String? = null,
) {
    val durationMs: Long?
        get() = runTimeTicks?.let { it / 10_000 }
}
