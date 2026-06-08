package guru.liquid.embysonic.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "emby_sonic_settings")

/**
 * Persists user settings (server/coordinator URLs, Emby session token, identity).
 *
 * Also keeps an in-memory [snapshot] of the latest values so OkHttp interceptors —
 * which run on arbitrary threads and cannot suspend — can read the current base
 * URL and token synchronously. The cache is seeded once (blocking) on first access
 * and refreshed on every write.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val COORDINATOR_URL = stringPreferencesKey("coordinator_url")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toAppSettings() }

    @Volatile
    private var cached: AppSettings? = null

    /** Latest settings, read synchronously. Seeds the cache on first call. */
    fun snapshot(): AppSettings {
        cached?.let { return it }
        return runBlocking { settings.first() }.also { cached = it }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val deviceId = this[Keys.DEVICE_ID] ?: UUID.randomUUID().toString()
        return AppSettings(
            serverUrl = this[Keys.SERVER_URL],
            coordinatorUrl = this[Keys.COORDINATOR_URL],
            accessToken = this[Keys.ACCESS_TOKEN],
            userId = this[Keys.USER_ID],
            userName = this[Keys.USER_NAME],
            deviceId = deviceId,
        )
    }

    /** Ensures a stable device id exists (used in the X-Emby-Authorization header). */
    suspend fun ensureDeviceId(): String {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_ID].isNullOrBlank()) {
                prefs[Keys.DEVICE_ID] = UUID.randomUUID().toString()
            }
        }
        return refreshCache().deviceId
    }

    suspend fun saveSession(
        serverUrl: String,
        accessToken: String,
        userId: String,
        userName: String,
        coordinatorUrl: String,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = serverUrl
            prefs[Keys.COORDINATOR_URL] = coordinatorUrl
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.USER_ID] = userId
            prefs[Keys.USER_NAME] = userName
        }
        refreshCache()
    }

    suspend fun saveCoordinatorUrl(url: String) {
        context.dataStore.edit { it[Keys.COORDINATOR_URL] = url }
        refreshCache()
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USER_NAME)
        }
        refreshCache()
    }

    private suspend fun refreshCache(): AppSettings =
        settings.first().also { cached = it }
}
