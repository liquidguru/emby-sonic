package guru.liquid.embysonic.data.emby.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackReportDto(
    @SerialName("ItemId")
    val itemId: String,

    @SerialName("PositionTicks")
    val positionTicks: Long,

    @SerialName("PlaySessionId")
    val playSessionId: String,

    @SerialName("MediaSourceId")
    val mediaSourceId: String? = "mediasource_$itemId",

    @SerialName("QueueableMediaTypes")
    val queueableMediaTypes: List<String> = listOf("Audio"),

    @SerialName("CanSeek")
    val canSeek: Boolean = true,

    @SerialName("IsPaused")
    val isPaused: Boolean? = null,

    @SerialName("IsMuted")
    val isMuted: Boolean? = false,

    @SerialName("PlayMethod")
    val playMethod: String? = "DirectPlay",

    @SerialName("PlaylistIndex")
    val playlistIndex: Int? = null,

    @SerialName("PlaylistLength")
    val playlistLength: Int? = null,

    @SerialName("EventName")
    val eventName: String? = null,
)
