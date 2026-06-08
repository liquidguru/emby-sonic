package guru.liquid.embysonic.data.emby.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NameIdPair(
    @SerialName("Id")
    val id: String? = null,

    @SerialName("Name")
    val name: String? = null,
)
