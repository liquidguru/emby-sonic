package guru.liquid.embysonic.data.emby.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthenticateRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

@Serializable
data class AuthenticateResponse(
    @SerialName("User") val user: EmbyUser,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
data class EmbyUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
)

@Serializable
data class SystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
)
