package guru.liquid.embysonic.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import guru.liquid.embysonic.data.emby.ContentKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.resumeStartItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns persistently downloaded playlists: the audio files live in
 * `filesDir/downloads/` (never auto-evicted, unlike [OfflinePrefetchCache]), and
 * a JSON index alongside them records the playlist/track metadata so a queue can
 * be rebuilt and played with no network.
 *
 * The index is read synchronously on construction so [localUri] — called from
 * the playback engine on the main thread — can resolve a downloaded file without
 * suspending. Mutations update the in-memory [state] immediately and persist the
 * index on IO.
 */
@Singleton
class DownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val progressStore: DownloadProgressStore,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val downloadsDir = File(context.filesDir, DIR_NAME).also { it.mkdirs() }
    private val indexFile = File(downloadsDir, INDEX_FILE)
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow(loadIndexBlocking())
    val state: StateFlow<DownloadIndex> = _state.asStateFlow()

    /** trackId -> absolute file path for COMPLETE tracks whose file still exists. */
    @Volatile
    private var fileIndex: Map<String, String> = buildFileIndex(_state.value)

    /** A local file URI for a downloaded, complete track, or null if not available. */
    fun localUri(trackId: String): Uri? {
        val path = fileIndex[trackId] ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) Uri.fromFile(file) else null
    }

    fun isTrackDownloaded(trackId: String): Boolean = localUri(trackId) != null

    fun playlist(playlistId: String): DownloadedPlaylist? =
        _state.value.playlists.firstOrNull { it.playlistId == playlistId }

    /** A downloaded playlist's complete tracks as playable items (local art preferred). */
    fun playableItems(playlistId: String): List<LibraryItem> =
        playlist(playlistId)?.tracks
            ?.filter { it.state == DownloadState.COMPLETE }
            ?.map { it.toLibraryItem() }
            .orEmpty()

    /**
     * Where to start a downloaded collection: a music playlist plays from the top;
     * a book resumes at the chapter you were *most recently* in (by recorded
     * recency), falling back to the snapshot's resume heuristic, then the start.
     */
    fun startItem(playlistId: String, items: List<LibraryItem>): LibraryItem? {
        if (items.isEmpty()) return null
        if (playlist(playlistId)?.isAudiobook != true) return items.first()
        val recent = progressStore.latestInProgress(items.map { it.id })
        return items.firstOrNull { it.id == recent } ?: items.resumeStartItem() ?: items.first()
    }

    private fun DownloadedTrack.toLibraryItem(): LibraryItem {
        // Local progress (incl. positions recorded offline) wins over the snapshot's
        // download-time values, so an offline book resumes where you actually stopped.
        val local = progressStore.progress(id)
        return LibraryItem(
            id = id,
            title = title,
            subtitle = artist,
            imageUrl = artUri(this)?.toString() ?: imageUrl,
            album = album,
            durationMs = durationMs,
            playbackPositionMs = local?.positionMs ?: playbackPositionMs,
            played = local?.played ?: played,
            contentKind = runCatching { ContentKind.valueOf(contentKind) }.getOrDefault(ContentKind.UNKNOWN),
            // Keep the source container so the crossfade gate stays correct for
            // tracks played from downloads too (parity with the streamed paths).
            container = container,
        )
    }

    /** Destination file for a track; [container] sets the extension when known. */
    fun fileFor(trackId: String, container: String?): File {
        val ext = container?.lowercase()?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
        return File(downloadsDir, "$FILE_PREFIX${hash(trackId)}$ext")
    }

    /** Destination file for a cover image, keyed by URL so shared album art is one file. */
    fun artFileFor(imageUrl: String): File =
        File(downloadsDir, "$ART_PREFIX${hash(imageUrl)}.jpg")

    /** A local file URI for a track's downloaded cover art, or null if not present. */
    fun artUri(track: DownloadedTrack): Uri? {
        val name = track.artFileName ?: return null
        val file = File(downloadsDir, name)
        return if (file.exists() && file.length() > 0) Uri.fromFile(file) else null
    }

    /** Insert or replace a playlist entry (e.g. when a download is queued). */
    suspend fun putPlaylist(playlist: DownloadedPlaylist) = mutate { index ->
        index.copy(playlists = index.playlists.filter { it.playlistId != playlist.playlistId } + playlist)
    }

    /** Update a single track's state/size within a playlist. */
    suspend fun updateTrack(
        playlistId: String,
        trackId: String,
        state: DownloadState,
        sizeBytes: Long? = null,
    ) = mutate { index ->
        index.copy(
            playlists = index.playlists.map { pl ->
                if (pl.playlistId != playlistId) return@map pl
                pl.copy(
                    tracks = pl.tracks.map { tr ->
                        if (tr.id != trackId) tr
                        else tr.copy(state = state, sizeBytes = sizeBytes ?: tr.sizeBytes)
                    },
                )
            },
        )
    }

    /** Remove a playlist's download: delete its files and drop the index entry. */
    suspend fun removePlaylist(playlistId: String) {
        val entry = playlist(playlistId)
        if (entry != null) {
            withContext(Dispatchers.IO) {
                // Only delete files not still referenced by another downloaded playlist.
                val others = _state.value.playlists.filter { it.playlistId != playlistId }
                val keepFiles = others.flatMap { it.tracks }.map { it.fileName }.toSet()
                val keepArt = others.flatMap { it.tracks }.mapNotNull { it.artFileName }.toSet()
                entry.tracks.forEach { tr ->
                    if (tr.fileName !in keepFiles) File(downloadsDir, tr.fileName).delete()
                    tr.artFileName?.takeIf { it !in keepArt }?.let { File(downloadsDir, it).delete() }
                }
            }
            progressStore.clear(entry.tracks.map { it.id })
        }
        mutate { index -> index.copy(playlists = index.playlists.filter { it.playlistId != playlistId }) }
    }

    private suspend fun mutate(transform: (DownloadIndex) -> DownloadIndex) {
        writeMutex.withLock {
            val next = transform(_state.value)
            _state.value = next
            fileIndex = buildFileIndex(next)
            withContext(Dispatchers.IO) {
                runCatching {
                    downloadsDir.mkdirs()
                    indexFile.writeText(json.encodeToString(DownloadIndex.serializer(), next))
                }.onFailure { Log.w(TAG, "Failed to persist download index", it) }
            }
        }
    }

    private fun buildFileIndex(index: DownloadIndex): Map<String, String> {
        val map = HashMap<String, String>()
        index.playlists.forEach { pl ->
            pl.tracks.forEach { tr ->
                if (tr.state == DownloadState.COMPLETE) {
                    val file = File(downloadsDir, tr.fileName)
                    if (file.exists() && file.length() > 0) map[tr.id] = file.absolutePath
                }
            }
        }
        return map
    }

    private fun loadIndexBlocking(): DownloadIndex {
        if (!indexFile.exists()) return DownloadIndex()
        return runCatching {
            json.decodeFromString(DownloadIndex.serializer(), indexFile.readText())
        }.getOrElse {
            Log.w(TAG, "Corrupt download index, starting empty", it)
            DownloadIndex()
        }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "DownloadStore"
        const val DIR_NAME = "downloads"
        const val INDEX_FILE = "index.json"
        const val FILE_PREFIX = "dl_"
        const val ART_PREFIX = "art_"
    }
}
