package guru.liquid.embysonic.data.emby

import guru.liquid.embysonic.data.emby.dto.AuthenticateRequest
import guru.liquid.embysonic.data.emby.dto.AuthenticateResponse
import guru.liquid.embysonic.data.emby.dto.EmbyItemDto
import guru.liquid.embysonic.data.emby.dto.PlaybackReportDto
import guru.liquid.embysonic.data.emby.dto.PlaylistCreationResultDto
import guru.liquid.embysonic.data.emby.dto.QueryResult
import guru.liquid.embysonic.data.emby.dto.SystemInfo
import guru.liquid.embysonic.data.emby.dto.UserDataUpdateDto
import retrofit2.http.Body
import retrofit2.http.DELETE
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
        @Query("Fields") fields: String = "UserData,PrimaryImageAspectRatio",
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 200,
        @Query("ParentId") parentId: String? = null,
        @Query("AlbumArtistIds") albumArtistIds: String? = null,
        @Query("Filters") filters: String? = null,
        @Query("Years") years: String? = null,
    ): QueryResult<EmbyItemDto>

    /**
     * Fetches specific items by id (comma-separated). Used to hydrate artwork for
     * coordinator-supplied track ids (sonic mixes), which carry no image metadata.
     */
    @GET("Items")
    suspend fun getItemsByIds(
        @Query("UserId") userId: String,
        @Query("Ids") ids: String,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio",
    ): QueryResult<EmbyItemDto>

    /**
     * Items of a playlist, in playlist order. The dedicated endpoint preserves the
     * stored sequence (and PlaylistItemId) that a generic `/Items` query would not.
     */
    @GET("Playlists/{playlistId}/Items")
    suspend fun getPlaylistItems(
        @Path("playlistId") playlistId: String,
        @Query("UserId") userId: String,
        @Query("Fields") fields: String = "UserData,PrimaryImageAspectRatio",
        @Query("Limit") limit: Int = 2000,
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

    @POST("Sessions/Playing")
    suspend fun reportPlaybackStarted(@Body body: PlaybackReportDto)

    @POST("Sessions/Playing/Progress")
    suspend fun reportPlaybackProgress(@Body body: PlaybackReportDto)

    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(@Body body: PlaybackReportDto)

    @POST("Users/{userId}/Items/{itemId}/UserData")
    suspend fun updateUserData(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Body body: UserDataUpdateDto,
    )

    /** Deletes an Emby item (e.g. a playlist) from the server. */
    @DELETE("Items/{itemId}")
    suspend fun deleteItem(@Path("itemId") itemId: String)

    /** Album artists within a library ([parentId] scopes to that library). */
    @GET("Artists/AlbumArtists")
    suspend fun getAlbumArtists(
        @Query("UserId") userId: String,
        @Query("ParentId") parentId: String? = null,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Fields") fields: String = "UserData,PrimaryImageAspectRatio",
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 200,
    ): QueryResult<EmbyItemDto>
}
