package guru.liquid.embysonic.data.recent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One recorded playback session: the source that started it plus the exact list
 * of tracks that were queued, so Recent plays can put the same songs back even
 * for generated queues (radio/adventure) that would otherwise regenerate
 * differently. [key] de-dupes repeated plays of the same source.
 */
@Serializable
data class RecentPlay(
    val key: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String? = null,
    val trackIds: List<String>,
    val timestampMs: Long,
)

private val Context.recentPlaysDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "emby_sonic_recent_plays")

/**
 * Local history of recently played queues (most-recent first), capped and
 * de-duped by source. Backed by DataStore as a JSON blob. Audiobooks are never
 * recorded here (the caller skips them). This is the first piece of the play
 * history subsystem the spec deferred for features like "On This Day".
 */
@Singleton
class RecentPlaysRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val recentPlays: Flow<List<RecentPlay>> =
        context.recentPlaysDataStore.data.map { prefs -> decode(prefs[KEY]) }

    /** Record a play, moving it to the front and dropping any older entry with the same key. */
    suspend fun record(play: RecentPlay) {
        if (play.trackIds.isEmpty()) return
        context.recentPlaysDataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val deduped = current.filterNot { it.key == play.key }
            val updated = (listOf(play) + deduped).take(MAX_ENTRIES)
            prefs[KEY] = json.encodeToString(updated)
        }
    }

    suspend fun clear() {
        context.recentPlaysDataStore.edit { it.remove(KEY) }
    }

    private fun decode(raw: String?): List<RecentPlay> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<RecentPlay>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY = stringPreferencesKey("recent_plays_json")
        const val MAX_ENTRIES = 20
    }
}
