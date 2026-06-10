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

@Serializable
data class SonicMixDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("cluster_id") val clusterId: Int? = null,
    @SerialName("track_count") val trackCount: Int = 0,
)

@Serializable
data class SonicMixDetailDto(
    @SerialName("mix") val mix: SonicMixDto,
    @SerialName("tracks") val tracks: List<TrackOutDto> = emptyList(),
)

@Serializable
data class BuildMixesRequestDto(
    @SerialName("n_clusters") val nClusters: Int = 30,
    @SerialName("tracks_per_mix") val tracksPerMix: Int = 50,
)

@Serializable
data class BuildMixesStartedDto(
    @SerialName("message") val message: String,
    @SerialName("n_clusters") val nClusters: Int,
    @SerialName("tracks_per_mix") val tracksPerMix: Int,
)
