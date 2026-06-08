package guru.liquid.embysonic.data.coordinator.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A coordinator track record. [id] is the Emby item id, usable directly for playlists. */
@Serializable
data class TrackOutDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String? = null,
    @SerialName("artist") val artist: String? = null,
    @SerialName("album") val album: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

/** One result of `GET /sonic/tracks/{id}/similar`. */
@Serializable
data class SimilarTrackDto(
    @SerialName("track") val track: TrackOutDto,
    @SerialName("score") val score: Double = 0.0,
)

/** Response of `GET /sonic/tracks/{id}/radio`. */
@Serializable
data class RadioPlaylistDto(
    @SerialName("seed_id") val seedId: String,
    @SerialName("tracks") val tracks: List<TrackOutDto> = emptyList(),
)
