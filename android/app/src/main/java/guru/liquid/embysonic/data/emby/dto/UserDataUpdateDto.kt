package guru.liquid.embysonic.data.emby.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDataUpdateDto(
    @SerialName("PlaybackPositionTicks")
    val playbackPositionTicks: Long,

    @SerialName("Played")
    val played: Boolean = false,
)
