package guru.liquid.embysonic.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        val LIBRARY_LIST_VIEW = booleanPreferencesKey("library_list_view")
        val HOME_COMPACT_CARDS = booleanPreferencesKey("home_compact_cards")
        val HOME_SECTION_ORDER = stringPreferencesKey("home_section_order")
        val HOME_HIDDEN_SECTIONS = stringPreferencesKey("home_hidden_sections")
        val PLAYBACK_REPEAT_MODE = stringPreferencesKey("playback_repeat_mode")
        val CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
        val CROSSFADE_DURATION_MS = intPreferencesKey("crossfade_duration_ms")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_BAND_LEVELS = stringPreferencesKey("eq_band_levels")
        val GENERATED_MIX_TRACKS = intPreferencesKey("generated_mix_tracks")
        val AUDIOBOOK_SPEED = floatPreferencesKey("audiobook_speed")
        val THEME_CHOICE = stringPreferencesKey("theme_choice")
        val CAST_SERVER_URL = stringPreferencesKey("cast_server_url")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toAppSettings() }

    /** Whether library/detail grids render as a list instead of cards (persisted, shared). */
    val libraryListView: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.LIBRARY_LIST_VIEW] ?: false }

    val homeCompactCards: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.HOME_COMPACT_CARDS] ?: false }

    val homeSectionOrder: Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.HOME_SECTION_ORDER]?.splitCsv().orEmpty()
        }

    val homeHiddenSections: Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.HOME_HIDDEN_SECTIONS]?.splitCsv()?.toSet().orEmpty()
        }

    val playbackRepeatMode: Flow<String> =
        context.dataStore.data.map { it[Keys.PLAYBACK_REPEAT_MODE] ?: "OFF" }

    /** Whether music tracks crossfade into each other. Never applies to audiobooks. */
    val crossfadeEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.CROSSFADE_ENABLED] ?: false }

    /** Crossfade overlap length in milliseconds (both tracks audible together). */
    val crossfadeDurationMs: Flow<Int> =
        context.dataStore.data.map { it[Keys.CROSSFADE_DURATION_MS] ?: DEFAULT_CROSSFADE_MS }

    /** Shared track-count choice for generated sonic mixes and genre mixes. */
    val generatedMixTracks: Flow<Int> =
        context.dataStore.data.map { it[Keys.GENERATED_MIX_TRACKS] ?: DEFAULT_GENERATED_MIX_TRACKS }

    val audiobookSpeed: Flow<Float> =
        context.dataStore.data.map { (it[Keys.AUDIOBOOK_SPEED] ?: DEFAULT_AUDIOBOOK_SPEED).coerceInAudioSpeed() }

    /** Selected app colour theme; drives the Compose color scheme app-wide. */
    val themeChoice: Flow<ThemeChoice> =
        context.dataStore.data.map { ThemeChoice.fromKey(it[Keys.THEME_CHOICE]) }

    /** Direct LAN Emby URL for casting; blank means fall back to [serverUrl]. */
    val castServerUrl: Flow<String?> =
        context.dataStore.data.map { it[Keys.CAST_SERVER_URL] }

    suspend fun setLibraryListView(value: Boolean) {
        context.dataStore.edit { it[Keys.LIBRARY_LIST_VIEW] = value }
    }

    suspend fun setHomeCompactCards(value: Boolean) {
        context.dataStore.edit { it[Keys.HOME_COMPACT_CARDS] = value }
    }

    suspend fun setHomeSectionOrder(sectionIds: List<String>) {
        context.dataStore.edit { it[Keys.HOME_SECTION_ORDER] = sectionIds.joinToString(",") }
    }

    suspend fun setHomeSectionVisible(sectionId: String, visible: Boolean) {
        context.dataStore.edit { prefs ->
            val hidden = prefs[Keys.HOME_HIDDEN_SECTIONS]?.splitCsv()?.toMutableSet() ?: mutableSetOf()
            if (visible) {
                hidden.remove(sectionId)
            } else {
                hidden.add(sectionId)
            }
            if (hidden.isEmpty()) {
                prefs.remove(Keys.HOME_HIDDEN_SECTIONS)
            } else {
                prefs[Keys.HOME_HIDDEN_SECTIONS] = hidden.joinToString(",")
            }
        }
    }

    suspend fun setPlaybackRepeatMode(mode: String) {
        context.dataStore.edit { it[Keys.PLAYBACK_REPEAT_MODE] = mode }
    }

    suspend fun setCrossfadeEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.CROSSFADE_ENABLED] = value }
        refreshCache()
    }

    suspend fun setCrossfadeDurationMs(value: Int) {
        context.dataStore.edit { it[Keys.CROSSFADE_DURATION_MS] = value }
        refreshCache()
    }

    suspend fun setEqEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.EQ_ENABLED] = value }
        refreshCache()
    }

    suspend fun setEqBandLevels(levels: List<Int>) {
        context.dataStore.edit { it[Keys.EQ_BAND_LEVELS] = levels.joinToString(",") }
        refreshCache()
    }

    suspend fun setGeneratedMixTracks(value: Int) {
        context.dataStore.edit { it[Keys.GENERATED_MIX_TRACKS] = value }
    }

    suspend fun setAudiobookSpeed(value: Float) {
        context.dataStore.edit { it[Keys.AUDIOBOOK_SPEED] = value.coerceInAudioSpeed() }
        refreshCache()
    }

    suspend fun setThemeChoice(choice: ThemeChoice) {
        context.dataStore.edit { it[Keys.THEME_CHOICE] = choice.name }
        refreshCache()
    }

    suspend fun setCastServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            val trimmed = url.trim().trimEnd('/')
            if (trimmed.isEmpty()) prefs.remove(Keys.CAST_SERVER_URL) else prefs[Keys.CAST_SERVER_URL] = trimmed
        }
        refreshCache()
    }

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
            crossfadeEnabled = this[Keys.CROSSFADE_ENABLED] ?: false,
            crossfadeDurationMs = this[Keys.CROSSFADE_DURATION_MS] ?: DEFAULT_CROSSFADE_MS,
            eqEnabled = this[Keys.EQ_ENABLED] ?: false,
            eqBandLevels = this[Keys.EQ_BAND_LEVELS]
                ?.splitCsv()
                ?.mapNotNull { it.toIntOrNull() }
                .orEmpty(),
            generatedMixTracks = this[Keys.GENERATED_MIX_TRACKS] ?: DEFAULT_GENERATED_MIX_TRACKS,
            audiobookSpeed = (this[Keys.AUDIOBOOK_SPEED] ?: DEFAULT_AUDIOBOOK_SPEED).coerceInAudioSpeed(),
            themeChoice = ThemeChoice.fromKey(this[Keys.THEME_CHOICE]),
            castServerUrl = this[Keys.CAST_SERVER_URL],
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

    private fun String.splitCsv(): List<String> =
        split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        const val DEFAULT_CROSSFADE_MS = 6_000
        const val DEFAULT_GENERATED_MIX_TRACKS = 25
        const val DEFAULT_AUDIOBOOK_SPEED = 1f
    }
}

private fun Float.coerceInAudioSpeed(): Float =
    takeIf { it.isFinite() }?.coerceIn(0.75f, 2.0f) ?: 1f
