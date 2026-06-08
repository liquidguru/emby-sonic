package guru.liquid.embysonic.data.emby.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of `POST /Playlists`. */
@Serializable
data class PlaylistCreationResultDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("ItemAddedCount") val itemAddedCount: Int = 0,
)
