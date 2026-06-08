package guru.liquid.embysonic.data.emby

import guru.liquid.embysonic.data.emby.dto.AuthenticateRequest
import guru.liquid.embysonic.data.emby.dto.AuthenticateResponse
import guru.liquid.embysonic.data.emby.dto.EmbyItemDto
import guru.liquid.embysonic.data.emby.dto.PlaylistCreationResultDto
import guru.liquid.embysonic.data.emby.dto.QueryResult
import guru.liquid.embysonic.data.emby.dto.SystemInfo
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Direct Emby Server API. Stream/playback endpoints are added in M3.
 */
interface EmbyApi {

    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(@Body body: AuthenticateRequest): AuthenticateResponse

    @GET("System/Info/Public")
    suspend fun publicSystemInfo(): SystemInfo

    /** The user's libraries (each has Id + CollectionType: "music", "audiobooks", …). */
    @GET("Users/{userId}/Views")
    suspend fun getViews(@Path("userId") userId: String): QueryResult<EmbyItemDto>

    /**
     * Generic library query. [includeItemTypes] selects "MusicAlbum" or "Audio";
     * [parentId] scopes to an album's tracks, [albumArtistIds] to an artist's albums.
     */
    @GET("Items")
    suspend fun getItems(
        @Query("UserId") userId: String,
        @Query("IncludeItemTypes") includeItemTypes: String,
        @Query("Recursive") recursive: Boolean = true,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Fields") fields: String = "PrimaryImageAspectRatio",
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 200,
        @Query("ParentId") parentId: String? = null,
        @Query("AlbumArtistIds") albumArtistIds: String? = null,
    ): QueryResult<EmbyItemDto>

    /**
     * Creates an Emby playlist owned by [userId] with the given comma-separated
     * item [ids]. Persists server-side and appears in every Emby client.
     */
    @POST("Playlists")
    suspend fun createPlaylist(
        @Query("Name") name: String,
        @Query("Ids") ids: String,
        @Query("UserId") userId: String,
        @Query("MediaType") mediaType: String = "Audio",
    ): PlaylistCreationResultDto

    /** Album artists within a library ([parentId] scopes to that library). */
    @GET("Artists/AlbumArtists")
    suspend fun getAlbumArtists(
        @Query("UserId") userId: String,
        @Query("ParentId") parentId: String? = null,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Fields") fields: String = "PrimaryImageAspectRatio",
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 200,
    ): QueryResult<EmbyItemDto>
}
