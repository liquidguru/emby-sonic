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
) {
    val durationMs: Long?
        get() = runTimeTicks?.let { it / 10_000 }
}
