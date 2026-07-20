package guru.liquid.embysonic.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One persisted playback track. A deliberately standalone mirror of
 * `PlaybackTrack` so the on-disk format is owned here and isn't coupled to the
 * live playback model — a field change there can't silently corrupt saved
 * sessions, and the controller does the (cheap) mapping both ways. [contentKind]
 * is stored as the enum *name* so this layer needs no dependency on the playback
 * package.
 */
@Serializable
data class PersistedTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val imageUrl: String? = null,
    val durationMs: Long? = null,
    val playbackPositionMs: Long = 0,
    val contentKind: String = "UNKNOWN",
    val container: String? = null,
)

/**
 * A snapshot of the last playback session, enough to put the same queue back and
 * resume where the user left off after the app's process has been killed.
 * [positionMs] is the *absolute* position of the current track.
 */
@Serializable
data class PersistedSession(
    val tracks: List<PersistedTrack>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffled: Boolean = false,
)

private val Context.playbackSessionDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "emby_sonic_playback_session")

/**
 * Persists the last playback session as a JSON blob in DataStore so the
 * home-screen widget (and the app) can resume after Android reclaims the
 * process. Cleared on an explicit Stop. A malformed/old blob decodes to null,
 * so a bad save degrades to "no resume" rather than a crash.
 */
@Singleton
class PlaybackSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(session: PersistedSession) {
        if (session.tracks.isEmpty()) {
            clear()
            return
        }
        context.playbackSessionDataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(PersistedSession.serializer(), session)
        }
    }

    suspend fun load(): PersistedSession? =
        decode(context.playbackSessionDataStore.data.first()[KEY])

    suspend fun clear() {
        context.playbackSessionDataStore.edit { it.remove(KEY) }
    }

    private fun decode(raw: String?): PersistedSession? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(PersistedSession.serializer(), raw)
        }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }
    }

    private companion object {
        val KEY = stringPreferencesKey("playback_session_json")
    }
}
