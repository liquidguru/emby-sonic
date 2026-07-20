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
    // Source container (e.g. "wma"); drives the crossfade direct-play gate.
    @SerialName("container") val container: String? = null,
)

/** One result of `GET /sonic/tracks/{id}/similar`. */
@Serializable
data class SimilarTrackDto(
    @SerialName("track") val track: TrackOutDto,
    @SerialName("score") val score: Double = 0.0,
)

/** One result of `GET /sonic/artists/{id}/similar`. */
@Serializable
data class SimilarArtistDto(
    @SerialName("artist") val artist: String,
    @SerialName("score") val score: Double = 0.0,
)

/** One result of `GET /sonic/albums/{id}/similar`. */
@Serializable
data class SimilarAlbumDto(
    @SerialName("album") val album: String,
    @SerialName("artist") val artist: String? = null,
    @SerialName("score") val score: Double = 0.0,
)

@Serializable
data class QueueInjectRequestDto(
    @SerialName("current_track_id") val currentTrackId: String,
    @SerialName("queue_length") val queueLength: Int = 5,
)

@Serializable
data class QueueInjectDto(
    @SerialName("injected") val injected: List<TrackOutDto> = emptyList(),
)

/** Response of `GET /sonic/tracks/{id}/radio`. */
@Serializable
data class RadioPlaylistDto(
    @SerialName("seed_id") val seedId: String,
    @SerialName("tracks") val tracks: List<TrackOutDto> = emptyList(),
)

/** Request for `POST /sonic/adventure` — a sonic journey from one track to another. */
@Serializable
data class AdventureRequestDto(
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    @SerialName("length") val length: Int = 20,
)

/** Response of `POST /sonic/adventure`. */
@Serializable
data class AdventurePlaylistDto(
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    @SerialName("tracks") val tracks: List<TrackOutDto> = emptyList(),
)

/** Request for `POST /sonic/artists/mix` — a mix built from a set of chosen artists. */
@Serializable
data class ArtistMixRequestDto(
    @SerialName("artists") val artists: List<String>,
    @SerialName("per_artist") val perArtist: Int = 5,
    @SerialName("length") val length: Int? = null,
)

/** Response of `POST /sonic/artists/mix`. */
@Serializable
data class ArtistMixPlaylistDto(
    @SerialName("artists") val artists: List<String> = emptyList(),
    @SerialName("tracks") val tracks: List<TrackOutDto> = emptyList(),
)

@Serializable
data class SonicMixDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("cluster_id") val clusterId: Int? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    // A representative track id; clients resolve its Emby cover for the mix tile.
    @SerialName("cover_track_id") val coverTrackId: String? = null,
)

@Serializable
data class SonicMixDetailDto(
    @SerialName("mix") val mix: SonicMixDto,
    @SerialName("tracks") val tracks: List<TrackOutDto> = emptyList(),
)

@Serializable
data class RegenerateMixRequestDto(
    @SerialName("tracks_per_mix") val tracksPerMix: Int = 50,
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

@Serializable
data class BuildStateDto(
    @SerialName("running") val running: Boolean = false,
)

/** Request for `POST /sonic/tracks/loudness` — batch loudness lookup for a queue. */
@Serializable
data class LoudnessRequestDto(
    @SerialName("ids") val ids: List<String>,
)

/**
 * Response of `POST /sonic/tracks/loudness` — per-track playback data for a queue.
 *
 * [loudness] drives volume normalisation; [edges] drives crossfade edge trimming.
 * Both maps are sparse and independent — a track appears only if that value was
 * measured — so a missing id means "no data" (unity gain / blend against the
 * full duration), not an error.
 */
@Serializable
data class LoudnessResponseDto(
    @SerialName("loudness") val loudness: Map<String, Float> = emptyMap(),
    @SerialName("edges") val edges: Map<String, TrackEdgesDto> = emptyMap(),
)

/** Where a track's audible music starts/ends, so a blend lands on music not dead air. */
@Serializable
data class TrackEdgesDto(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
)
