package guru.liquid.embysonic.data.download

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Last-known listening position for one downloaded track. */
@Serializable
data class TrackProgress(
    val positionMs: Long,
    val played: Boolean = false,
    // false while the position has only been saved locally (e.g. listened offline)
    // and still needs pushing to Emby once back online.
    val syncedToServer: Boolean = true,
    // When this position was last recorded — used to resume an audiobook at the
    // chapter you were most recently in, not the first one with a saved position.
    val updatedAt: Long = 0,
)

/**
 * Persists per-track listening positions for downloaded long-form content so an
 * audiobook resumes at the right place even when listened to fully offline, and
 * those offline positions can be flushed to Emby on reconnect. Kept separate from
 * [DownloadStore]'s index so frequent position writes don't churn the snapshot.
 */
@Singleton
class DownloadProgressStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(File(context.filesDir, "downloads"), "progress.json")
    private val mutex = Mutex()

    @Volatile
    private var entries: Map<String, TrackProgress> = loadBlocking()

    @Volatile
    private var lastWriteMs = 0L

    /** Synchronous read for building a resume queue. */
    fun progress(trackId: String): TrackProgress? = entries[trackId]

    /**
     * Record a book's single resume point: [currentId] gets the position, earlier
     * chapters that had been started are cleared+marked played (so they don't pull
     * resume backward — locally and, once flushed, on Emby), and later chapters are
     * cleared. Chapters never touched are left pristine. [orderedIds] is the book's
     * chapter order.
     */
    suspend fun setBookmark(
        orderedIds: List<String>,
        currentId: String,
        positionMs: Long,
        played: Boolean,
        syncedToServer: Boolean,
        flushNow: Boolean,
    ) = mutex.withLock {
        val now = System.currentTimeMillis()
        val currentIndex = orderedIds.indexOf(currentId)
        val next = entries.toMutableMap()
        if (currentIndex < 0) {
            next[currentId] = TrackProgress(positionMs, played, syncedToServer, now)
        } else {
            orderedIds.forEachIndexed { i, id ->
                when {
                    // Only chapters we'd actually started (have an entry) get cleared,
                    // so a jump-ahead doesn't falsely mark untouched chapters played.
                    i < currentIndex -> next[id]?.let { existing ->
                        if (existing.positionMs != 0L || !existing.played) {
                            next[id] = TrackProgress(0, played = true, syncedToServer = false, now)
                        }
                    }
                    i == currentIndex -> next[id] = TrackProgress(positionMs, played, syncedToServer, now)
                    else -> next.remove(id)
                }
            }
        }
        entries = next
        if (flushNow || now - lastWriteMs >= MIN_WRITE_INTERVAL_MS) {
            persist()
            lastWriteMs = now
        }
    }

    /** The most recently played, still-in-progress track among [trackIds], if any. */
    fun latestInProgress(trackIds: Collection<String>): String? {
        val ids = trackIds.toSet()
        return entries.entries
            .filter { it.key in ids && !it.value.played && it.value.positionMs > MIN_RESUME_MS }
            .maxByOrNull { it.value.updatedAt }
            ?.key
    }

    /** Positions saved offline that still need pushing to the server. */
    fun unsynced(): Map<String, TrackProgress> = entries.filterValues { !it.syncedToServer }

    /**
     * Drop local positions for these tracks. Called on (re)download so a fresh
     * server position in the snapshot isn't masked by a stale local entry, and on
     * removal to clean up.
     */
    suspend fun clear(trackIds: Collection<String>) = mutex.withLock {
        val ids = trackIds.toSet()
        if (entries.keys.any { it in ids }) {
            entries = entries.filterKeys { it !in ids }
            persist()
        }
    }

    suspend fun markSynced(trackId: String) = mutex.withLock {
        val entry = entries[trackId] ?: return@withLock
        if (!entry.syncedToServer) {
            entries = entries + (trackId to entry.copy(syncedToServer = true))
            persist()
        }
    }

    // The file is tiny, so persisting inline under the mutex is fine.
    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString<Map<String, TrackProgress>>(entries))
        }.onFailure { Log.w(TAG, "Failed to persist download progress", it) }
    }

    private fun loadBlocking(): Map<String, TrackProgress> {
        if (!file.exists()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, TrackProgress>>(file.readText()) }
            .getOrElse {
                Log.w(TAG, "Corrupt download progress, starting empty", it)
                emptyMap()
            }
    }

    private companion object {
        const val TAG = "DownloadProgress"
        const val MIN_WRITE_INTERVAL_MS = 10_000L
        const val MIN_RESUME_MS = 5_000L
    }
}
