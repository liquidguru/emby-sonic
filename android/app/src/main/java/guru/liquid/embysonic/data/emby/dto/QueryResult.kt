package guru.liquid.embysonic.data.emby.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QueryResult<T>(
    @SerialName("Items")
    val items: List<T> = emptyList(),

    @SerialName("TotalRecordCount")
    val totalRecordCount: Int = 0,
)
