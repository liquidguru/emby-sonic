package guru.liquid.embysonic.data.emby.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDataDto(
    @SerialName("PlaybackPositionTicks")
    val playbackPositionTicks: Long? = null,

    @SerialName("PlayCount")
    val playCount: Int? = null,

    @SerialName("Played")
    val played: Boolean? = null,

    @SerialName("IsFavorite")
    val isFavorite: Boolean? = null,

    @SerialName("LastPlayedDate")
    val lastPlayedDate: String? = null,
)
