package guru.liquid.embysonic.data.coordinator

import guru.liquid.embysonic.data.coordinator.dto.AdventurePlaylistDto
import guru.liquid.embysonic.data.coordinator.dto.AdventureRequestDto
import guru.liquid.embysonic.data.coordinator.dto.ArtistMixPlaylistDto
import guru.liquid.embysonic.data.coordinator.dto.ArtistMixRequestDto
import guru.liquid.embysonic.data.coordinator.dto.RadioPlaylistDto
import guru.liquid.embysonic.data.coordinator.dto.BuildMixesRequestDto
import guru.liquid.embysonic.data.coordinator.dto.BuildMixesStartedDto
import guru.liquid.embysonic.data.coordinator.dto.BuildStateDto
import guru.liquid.embysonic.data.coordinator.dto.QueueInjectDto
import guru.liquid.embysonic.data.coordinator.dto.QueueInjectRequestDto
import guru.liquid.embysonic.data.coordinator.dto.RegenerateMixRequestDto
import guru.liquid.embysonic.data.coordinator.dto.SimilarAlbumDto
import guru.liquid.embysonic.data.coordinator.dto.SimilarArtistDto
import guru.liquid.embysonic.data.coordinator.dto.SimilarTrackDto
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDetailDto
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDto
import guru.liquid.embysonic.data.coordinator.dto.SonicStatus
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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

    /** Artists sonically similar to the artist of a representative [trackId]. */
    @GET("sonic/artists/{id}/similar")
    suspend fun similarArtists(
        @Path("id") trackId: String,
        @Query("n") n: Int = 10,
    ): List<SimilarArtistDto>

    /** Albums sonically similar to the album of a representative [trackId]. */
    @GET("sonic/albums/{id}/similar")
    suspend fun similarAlbums(
        @Path("id") trackId: String,
        @Query("n") n: Int = 10,
    ): List<SimilarAlbumDto>

    /** A radio-style sequence seeded from [trackId]. */
    @GET("sonic/tracks/{id}/radio")
    suspend fun trackRadio(
        @Path("id") trackId: String,
        @Query("length") length: Int = 25,
    ): RadioPlaylistDto

    /** A sonic journey that morphs from one track to another. */
    @POST("sonic/adventure")
    suspend fun adventure(@Body body: AdventureRequestDto): AdventurePlaylistDto

    /** A mix sequenced from a set of chosen artists (Artist Mix Creator). */
    @POST("sonic/artists/mix")
    suspend fun artistMix(@Body body: ArtistMixRequestDto): ArtistMixPlaylistDto

    @GET("sonic/mixes")
    suspend fun mixes(): List<SonicMixDto>

    @GET("sonic/mixes/{id}")
    suspend fun mixDetail(
        @Path("id") mixId: String,
    ): SonicMixDetailDto

    @POST("sonic/mixes/{id}/regenerate")
    suspend fun regenerateMix(
        @Path("id") mixId: String,
        @Body body: RegenerateMixRequestDto,
    ): SonicMixDetailDto

    @POST("sonic/library/build-mixes")
    suspend fun buildMixes(
        @Body body: BuildMixesRequestDto,
    ): BuildMixesStartedDto

    /** Whether a mix build is currently running. Poll after [buildMixes]. */
    @GET("sonic/library/build-state")
    suspend fun buildState(): BuildStateDto

    /** Guest DJ: inject similar tracks into the current queue. */
    @POST("sonic/queue/inject")
    suspend fun injectQueue(
        @Body body: QueueInjectRequestDto,
    ): QueueInjectDto

    // --- Waveform (design-for-A placeholder; see docs/spec.md) -------------------
    // Reserved from day one so real waveforms drop in without restructuring the
    // client. The MVP Now Playing screen never calls this — it renders a plain
    // progress bar via the TrackProgress interface. When the coordinator adds the
    // `GET /sonic/tracks/{id}/waveform` route, uncomment and implement.
    //
    // @GET("sonic/tracks/{id}/waveform")
    // suspend fun waveform(@Path("id") trackId: String): WaveformDto
}
