package guru.liquid.embysonic.data.coordinator

import guru.liquid.embysonic.data.coordinator.dto.SonicStatus
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Emby Sonic coordinator API (FastAPI, all routes under /sonic).
 * Similarity / radio / adventure / mixes endpoints are added in M4.
 */
interface CoordinatorApi {

    @GET("sonic/status")
    suspend fun status(): SonicStatus

    // --- Waveform (design-for-A placeholder; see docs/spec.md) -------------------
    // Reserved from day one so real waveforms drop in without restructuring the
    // client. The MVP Now Playing screen never calls this — it renders a plain
    // progress bar via the TrackProgress interface. When the coordinator adds the
    // `GET /sonic/tracks/{id}/waveform` route, uncomment and implement.
    //
    // @GET("sonic/tracks/{id}/waveform")
    // suspend fun waveform(@Path("id") trackId: String): WaveformDto
}
