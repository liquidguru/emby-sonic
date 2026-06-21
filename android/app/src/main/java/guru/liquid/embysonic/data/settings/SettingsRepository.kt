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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
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
    private val secureTokenStore: SecureTokenStore,
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val COORDINATOR_URL = stringPreferencesKey("coordinator_url")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val LEGACY_ENCRYPTED_ACCESS_TOKEN = stringPreferencesKey("access_token_encrypted")
        val SESSION_TOKEN_CIPHERTEXT = stringPreferencesKey("session_token_ciphertext")
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
        val EQ_PRESET = intPreferencesKey("eq_preset")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val VOLUME_NORMALIZATION = booleanPreferencesKey("volume_normalization")
        val PREFETCH_COUNT = intPreferencesKey("prefetch_count")
        val GENERATED_MIX_TRACKS = intPreferencesKey("generated_mix_tracks")
        val AUDIOBOOK_SPEED = floatPreferencesKey("audiobook_speed")
        val THEME_CHOICE = stringPreferencesKey("theme_choice")
        val CAST_SERVER_URL = stringPreferencesKey("cast_server_url")
    }

    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        migrationScope.launch { migratePlaintextAccessToken() }
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

    /** Active built-in preset index, or a negative value for a manual/custom curve. */
    suspend fun setEqPreset(preset: Int) {
        context.dataStore.edit { it[Keys.EQ_PRESET] = preset }
        refreshCache()
    }

    /** Whether offline downloads are restricted to unmetered (Wi-Fi) networks. */
    val downloadWifiOnly: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DOWNLOAD_WIFI_ONLY] ?: true }

    suspend fun setDownloadWifiOnly(value: Boolean) {
        context.dataStore.edit { it[Keys.DOWNLOAD_WIFI_ONLY] = value }
        refreshCache()
    }

    /**
     * Whether per-track volume normalisation is applied during playback. Uses the
     * coordinator's measured loudness (LUFS) to level tracks to a common target,
     * so a quiet track and a loud master play back at a similar perceived volume.
     */
    val volumeNormalizationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.VOLUME_NORMALIZATION] ?: true }

    suspend fun setVolumeNormalizationEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.VOLUME_NORMALIZATION] = value }
        refreshCache()
    }

    /**
     * How many upcoming queue tracks to pre-cache for gap-free playback through
     * brief connectivity drops. Higher = more resilient to skipping ahead with no
     * signal, at the cost of more data/storage. One of [PREFETCH_COUNT_OPTIONS].
     */
    val prefetchAheadCount: Flow<Int> =
        context.dataStore.data.map { (it[Keys.PREFETCH_COUNT] ?: DEFAULT_PREFETCH_COUNT).coerceInPrefetch() }

    suspend fun setPrefetchAheadCount(value: Int) {
        context.dataStore.edit { it[Keys.PREFETCH_COUNT] = value.coerceInPrefetch() }
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
        val accessToken = (this[Keys.SESSION_TOKEN_CIPHERTEXT] ?: this[Keys.LEGACY_ENCRYPTED_ACCESS_TOKEN])
            ?.let { secureTokenStore.decrypt(it) }
            ?: this[Keys.ACCESS_TOKEN]
        return AppSettings(
            serverUrl = this[Keys.SERVER_URL],
            coordinatorUrl = this[Keys.COORDINATOR_URL],
            accessToken = accessToken,
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
            eqPreset = this[Keys.EQ_PRESET] ?: -1,
            downloadWifiOnly = this[Keys.DOWNLOAD_WIFI_ONLY] ?: true,
            volumeNormalizationEnabled = this[Keys.VOLUME_NORMALIZATION] ?: true,
            prefetchAheadCount = (this[Keys.PREFETCH_COUNT] ?: DEFAULT_PREFETCH_COUNT).coerceInPrefetch(),
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
            prefs[Keys.SESSION_TOKEN_CIPHERTEXT] = secureTokenStore.encrypt(accessToken)
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.LEGACY_ENCRYPTED_ACCESS_TOKEN)
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
            prefs.remove(Keys.LEGACY_ENCRYPTED_ACCESS_TOKEN)
            prefs.remove(Keys.SESSION_TOKEN_CIPHERTEXT)
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USER_NAME)
        }
        refreshCache()
    }

    private suspend fun migratePlaintextAccessToken() {
        context.dataStore.edit { prefs ->
            val plaintext = prefs[Keys.ACCESS_TOKEN]
            val encrypted = prefs[Keys.SESSION_TOKEN_CIPHERTEXT]
            val legacyEncrypted = prefs[Keys.LEGACY_ENCRYPTED_ACCESS_TOKEN]
            if (encrypted.isNullOrBlank()) {
                when {
                    !legacyEncrypted.isNullOrBlank() -> {
                        prefs[Keys.SESSION_TOKEN_CIPHERTEXT] = legacyEncrypted
                    }
                    !plaintext.isNullOrBlank() -> {
                        prefs[Keys.SESSION_TOKEN_CIPHERTEXT] = secureTokenStore.encrypt(plaintext)
                    }
                }
            }
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.LEGACY_ENCRYPTED_ACCESS_TOKEN)
        }
        refreshCache()
    }

    private suspend fun refreshCache(): AppSettings =
        settings.first().also { cached = it }

    private fun String.splitCsv(): List<String> =
        split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_CROSSFADE_MS = 6_000
        const val DEFAULT_GENERATED_MIX_TRACKS = 25
        const val DEFAULT_AUDIOBOOK_SPEED = 1f
        const val DEFAULT_PREFETCH_COUNT = 3
        val PREFETCH_COUNT_OPTIONS = listOf(3, 5, 10, 15)
    }
}

private fun Float.coerceInAudioSpeed(): Float =
    takeIf { it.isFinite() }?.coerceIn(0.75f, 2.0f) ?: 1f

private fun Int.coerceInPrefetch(): Int =
    SettingsRepository.PREFETCH_COUNT_OPTIONS.minByOrNull { kotlin.math.abs(it - this) }
        ?: SettingsRepository.DEFAULT_PREFETCH_COUNT
