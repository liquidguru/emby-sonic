package guru.liquid.embysonic.data.coordinator

import guru.liquid.embysonic.data.coordinator.dto.RadioPlaylistDto
import guru.liquid.embysonic.data.coordinator.dto.SimilarTrackDto
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDetailDto
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDto
import guru.liquid.embysonic.data.coordinator.dto.SonicStatus
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Emby Sonic coordinator API (FastAPI, all routes under /sonic).
 */
interface CoordinatorApi {

    @GET("sonic/status")
    suspend fun status(): SonicStatus

    /** Tracks sonically similar to [trackId] (highest score first). */
    @GET("sonic/tracks/{id}/similar")
    suspend fun similarTracks(
        @Path("id") trackId: String,
        @Query("n") n: Int = 25,
    ): List<SimilarTrackDto>

    /** A radio-style sequence seeded from [trackId]. */
    @GET("sonic/tracks/{id}/radio")
    suspend fun trackRadio(
        @Path("id") trackId: String,
        @Query("length") length: Int = 25,
    ): RadioPlaylistDto

    @GET("sonic/mixes")
    suspend fun mixes(): List<SonicMixDto>

    @GET("sonic/mixes/{id}")
    suspend fun mixDetail(
        @Path("id") mixId: String,
    ): SonicMixDetailDto

    // --- Waveform (design-for-A placeholder; see docs/spec.md) -------------------
    // Reserved from day one so real waveforms drop in without restructuring the
    // client. The MVP Now Playing screen never calls this — it renders a plain
    // progress bar via the TrackProgress interface. When the coordinator adds the
    // `GET /sonic/tracks/{id}/waveform` route, uncomment and implement.
    //
    // @GET("sonic/tracks/{id}/waveform")
    // suspend fun waveform(@Path("id") trackId: String): WaveformDto
}
