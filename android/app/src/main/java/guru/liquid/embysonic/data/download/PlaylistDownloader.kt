package guru.liquid.embysonic.data.download

import android.net.Uri
import android.os.Build
import android.util.Log
import guru.liquid.embysonic.BuildConfig
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads whole playlists for offline listening. Resolves a playlist's tracks
 * via the Emby API, persists a browsable snapshot through [DownloadStore], then
 * fetches each track's **original source file** (`Static=true` — no transcode)
 * into `filesDir/downloads/`. Tracks download sequentially within a playlist;
 * progress is observed through [DownloadStore.state].
 */
@Singleton
class PlaylistDownloader @Inject constructor(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val store: DownloadStore,
    private val progress: DownloadProgressStore,
    private val notifier: DownloadNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobsMutex = Mutex()
    private val activeJobs = mutableMapOf<String, Job>()

    /** True while a download for this playlist is queued or running. */
    suspend fun isActive(playlistId: String): Boolean =
        jobsMutex.withLock { activeJobs.containsKey(playlistId) }

    /**
     * Queue a collection for offline download. [detailKind] is `PLAYLIST_TRACKS` for
     * a music playlist or `BOOK_CHAPTERS` for an audiobook. A no-op if already in flight.
     */
    fun downloadPlaylist(
        playlistId: String,
        name: String,
        coverUrl: String?,
        detailKind: DetailKind = DetailKind.PLAYLIST_TRACKS,
    ) {
        scope.launch {
            val start = jobsMutex.withLock {
                if (activeJobs.containsKey(playlistId)) {
                    false
                } else {
                    activeJobs[playlistId] = currentCoroutineContext()[Job]!!
                    true
                }
            }
            if (!start) return@launch
            try {
                runDownload(playlistId, name, coverUrl, detailKind)
            } catch (e: Exception) {
                Log.w(TAG, "Download of $playlistId stopped: ${e.message}")
            } finally {
                jobsMutex.withLock { activeJobs.remove(playlistId) }
            }
        }
    }

    /** Cancel an in-flight download (already-downloaded files are kept). */
    fun cancel(playlistId: String) {
        scope.launch {
            jobsMutex.withLock { activeJobs[playlistId] }?.cancel()
        }
    }

    private suspend fun runDownload(
        playlistId: String,
        name: String,
        coverUrl: String?,
        detailKind: DetailKind,
    ) {
        val items = runCatching { library.playableItems(playlistId, detailKind) }
            .getOrElse {
                Log.w(TAG, "Could not resolve $playlistId for download", it)
                return
            }
            .filter { it.id.isNotBlank() }
        if (items.isEmpty()) return

        val tracks = items.map { item ->
            DownloadedTrack(
                id = item.id,
                title = item.title,
                artist = item.subtitle,
                album = item.album,
                imageUrl = item.imageUrl,
                durationMs = item.durationMs,
                playbackPositionMs = item.playbackPositionMs,
                played = item.played,
                contentKind = item.contentKind.name,
                fileName = store.fileFor(item.id, null).name,
                artFileName = item.imageUrl?.let { store.artFileFor(it).name },
                state = DownloadState.PENDING,
            )
        }
        store.putPlaylist(
            DownloadedPlaylist(
                playlistId = playlistId,
                name = name,
                coverUrl = coverUrl,
                downloadedAt = System.currentTimeMillis(),
                contentKind = if (detailKind == DetailKind.BOOK_CHAPTERS) "AUDIOBOOK" else "MUSIC",
                tracks = tracks,
            ),
        )
        // The snapshot now holds the authoritative server position; drop any stale
        // local progress so it isn't masked by a previous download's leftover.
        progress.clear(tracks.map { it.id })

        var completed = 0
        for (track in tracks) {
            currentCoroutineContext().ensureActive()
            if (store.isTrackDownloaded(track.id)) {
                store.updateTrack(playlistId, track.id, DownloadState.COMPLETE)
                completed++
                continue
            }
            store.updateTrack(playlistId, track.id, DownloadState.DOWNLOADING)
            val size = runCatching { downloadTrack(track) }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "Download failed for ${track.id}", it)
                -1L
            }
            // Cover art is best-effort and never fails the track download.
            track.imageUrl?.let { url ->
                val artFile = store.artFileFor(url)
                if (!artFile.exists()) runCatching { downloadFile(url, artFile, withAuth = false) }
            }
            store.updateTrack(
                playlistId,
                track.id,
                if (size > 0) DownloadState.COMPLETE else DownloadState.FAILED,
                size.takeIf { it > 0 },
            )
            if (size > 0) completed++
        }
        // Reached only on a normal finish — a cancel throws out of the loop above and
        // never notifies, which is what we want.
        notifier.notifyDownloadFinished(
            name = name,
            completed = completed,
            total = tracks.size,
        )
    }

    /** Fetch the original source file with auth; returns bytes written, or -1 on failure. */
    private suspend fun downloadTrack(track: DownloadedTrack): Long =
        downloadFile(originalFileUrl(track.id), store.fileFor(track.id, track.container), withAuth = true)

    /** Stream [url] to [destination] via a temp file; returns bytes written, or -1 on failure. */
    private suspend fun downloadFile(url: String, destination: File, withAuth: Boolean): Long =
        withContext(Dispatchers.IO) {
            val temp = File(destination.parentFile, "${destination.name}.${System.nanoTime()}.tmp")
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    if (withAuth) playbackHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
                }
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "Download HTTP ${connection.responseCode} for $url")
                    return@withContext -1L
                }
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (temp.length() <= 0L) return@withContext -1L
                if (destination.exists()) destination.delete()
                if (!temp.renameTo(destination)) {
                    temp.copyTo(destination, overwrite = true)
                    temp.delete()
                }
                destination.length()
            } finally {
                if (temp.exists()) temp.delete()
            }
        }

    /** `/Audio/{id}/stream?Static=true` returns the unmodified source file. */
    private fun originalFileUrl(itemId: String): String {
        val base = settings.snapshot().serverUrl?.trimEnd('/')
            ?: throw IllegalStateException("No Emby server configured")
        return Uri.parse("$base/Audio/${Uri.encode(itemId)}/stream")
            .buildUpon()
            .appendQueryParameter("Static", "true")
            .build()
            .toString()
    }

    private fun playbackHeaders(): Map<String, String> {
        val snap = settings.snapshot()
        return linkedMapOf(
            "X-Emby-Authorization" to buildString {
                append("MediaBrowser ")
                append("Client=\"liquidWave\", ")
                append("Device=\"${Build.MODEL}\", ")
                append("DeviceId=\"${snap.deviceId}\", ")
                append("Version=\"${BuildConfig.VERSION_NAME}\"")
            },
        ).also { headers ->
            snap.accessToken?.takeIf { it.isNotBlank() }?.let { headers["X-Emby-Token"] = it }
        }
    }

    private companion object {
        const val TAG = "PlaylistDownloader"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
