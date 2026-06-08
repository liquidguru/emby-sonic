package guru.liquid.embysonic.data.coordinator.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response of `GET /sonic/status`. Unknown fields are ignored, so the coordinator
 * can add stats without breaking the client.
 */
@Serializable
data class SonicStatus(
    @SerialName("total_tracks") val total: Int = 0,
    @SerialName("analysed_tracks") val analysed: Int = 0,
    @SerialName("pending_tracks") val pending: Int = 0,
    @SerialName("scan_running") val scanRunning: Boolean = false,
    @SerialName("scan_progress") val scanProgress: Float = 0f,
) {
    /** Prefer the coordinator's own progress; fall back to analysed/total. */
    val progressFraction: Float
        get() = when {
            scanProgress > 0f -> scanProgress
            total > 0 -> analysed.toFloat() / total
            else -> 0f
        }
}
