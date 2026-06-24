package guru.liquid.embysonic.data.download

import kotlinx.serialization.Serializable

/** Per-track download progress within a playlist. */
enum class DownloadState { PENDING, DOWNLOADING, COMPLETE, FAILED }

/**
 * One downloaded track. A standalone, serializable mirror of the playback /
 * library track (like `PersistedTrack`) so the on-disk format is owned here and
 * carries everything needed to rebuild a playable queue with no network.
 * [contentKind] is stored as the enum *name* so this layer stays decoupled from
 * the playback package. [fileName] is relative to the downloads directory.
 */
@Serializable
data class DownloadedTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val imageUrl: String? = null,
    val durationMs: Long? = null,
    // Resume position + played flag captured at download time, so an offline book
    // resumes at the right chapter/position (see resumeStartItem).
    val playbackPositionMs: Long = 0,
    val played: Boolean = false,
    val contentKind: String = "UNKNOWN",
    // Source container (e.g. "flac", "mp3") for display and the local extension.
    val container: String? = null,
    val fileName: String,
    // Local cover-art file (relative to the downloads dir), for fully-offline art.
    val artFileName: String? = null,
    val sizeBytes: Long = 0,
    val state: DownloadState = DownloadState.PENDING,
)

/** A downloaded playlist plus enough metadata to browse and play it offline. */
@Serializable
data class DownloadedPlaylist(
    val playlistId: String,
    val name: String,
    val coverUrl: String? = null,
    val downloadedAt: Long = 0,
    // "MUSIC" for a playlist, "AUDIOBOOK" for a downloaded book.
    val contentKind: String = "MUSIC",
    val tracks: List<DownloadedTrack> = emptyList(),
) {
    val isAudiobook: Boolean get() = contentKind == "AUDIOBOOK"
    val completeCount: Int get() = tracks.count { it.state == DownloadState.COMPLETE }
    val failedCount: Int get() = tracks.count { it.state == DownloadState.FAILED }
    val isComplete: Boolean get() = tracks.isNotEmpty() && completeCount == tracks.size
    val isDownloading: Boolean
        get() = tracks.any { it.state == DownloadState.PENDING || it.state == DownloadState.DOWNLOADING }
    val totalBytes: Long get() = tracks.sumOf { it.sizeBytes }
}

/** The full on-disk index of every downloaded playlist. */
@Serializable
data class DownloadIndex(
    val playlists: List<DownloadedPlaylist> = emptyList(),
) {
    val totalBytes: Long get() = playlists.sumOf { it.totalBytes }
}
