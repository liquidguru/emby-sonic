package guru.liquid.embysonic.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.helperLatencyDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "emby_sonic_helper_latency")

/**
 * Remembers the crossfade helper's measured start latency, keyed by audio output
 * route.
 *
 * The controller learns this figure from each blend's residual, but it used to
 * live only in memory: every fresh playback process began from a hardcoded guess
 * and needed two or three crossfades to converge, so the first few transitions of
 * every drive or ride sounded wrong before settling. Persisting it means a new
 * session starts already calibrated.
 *
 * It's keyed by route because the latency is device/codec dependent and differs
 * substantially between, say, Bluetooth in the car and the phone's own speaker —
 * a single shared figure would be freshly wrong every time the output changed,
 * which is exactly the case this is meant to fix.
 *
 * A malformed or missing blob decodes to an empty map, so a bad save degrades to
 * "learn it again from the default guess" rather than a crash.
 */
@Singleton
class HelperLatencyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    suspend fun load(): Map<String, Long> {
        val raw = context.helperLatencyDataStore.data.first()[KEY]
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    suspend fun save(byRoute: Map<String, Long>) {
        context.helperLatencyDataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(serializer, byRoute)
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("helper_start_latency_by_route_json")
    }
}
