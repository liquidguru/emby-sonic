package guru.liquid.embysonic.playback

import guru.liquid.embysonic.data.emby.ContentKind
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem

data class PlaybackTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val imageUrl: String?,
    val durationMs: Long?,
    val playbackPositionMs: Long = 0,
    val contentKind: ContentKind = ContentKind.UNKNOWN,
    // Source container (e.g. "mp3", "wma"); null if unknown. Drives the crossfade
    // direct-play gate — a track Emby will transcode is skipped for crossfade.
    val container: String? = null,
)

enum class PlaybackRepeatMode {
    OFF,
    ALL,
    ONE,
}

enum class SleepTimerMode {
    OFF,
    TIMED,
    END_OF_TRACK,
}

data class CastVolumeState(
    val available: Boolean = false,
    val volume: Float = 1f,
    val deviceName: String? = null,
    val pending: Boolean = false,
)

enum class OfflinePrefetchStatus {
    IDLE,
    WARMING,
    READY,
    UNAVAILABLE,
}

data class OfflinePrefetchState(
    val status: OfflinePrefetchStatus = OfflinePrefetchStatus.IDLE,
    val readyCount: Int = 0,
    val targetCount: Int = 0,
)

/**
 * Describes where a playback queue came from, so it can be recorded in the
 * Recent plays history. [key] is a stable identity for de-duping repeated plays
 * of the same thing (e.g. "playlist:<id>", "mix:<id>", "album:<id>", "adventure",
 * "radio:<seedId>", "station:library", "track:<id>"). Audiobook plays are never
 * recorded — the controller skips them by content kind.
 */
data class PlaybackSource(
    val key: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String? = null,
)

/**
 * Recent-plays source for a collection [item] played as the given [kind], or
 * null for audiobooks (never recorded). [cover] overrides the item's own art
 * (e.g. the playing track's cover) when supplied.
 */
fun playbackSourceFor(kind: DetailKind, item: LibraryItem, cover: String? = item.imageUrl): PlaybackSource? =
    when (kind) {
        DetailKind.ALBUM_TRACKS -> PlaybackSource("album:${item.id}", item.title, "Album", cover)
        DetailKind.ARTIST_ALBUMS -> PlaybackSource("artist:${item.id}", item.title, "Artist", cover)
        DetailKind.GENRE_TRACKS -> PlaybackSource("genre:${item.title}", item.title, "Genre", cover)
        DetailKind.PLAYLIST_TRACKS -> PlaybackSource("playlist:${item.id}", item.title, "Playlist", cover)
        DetailKind.AUTHOR_BOOKS, DetailKind.BOOK_CHAPTERS -> null
    }

data class PlaybackUiState(
    val currentTrack: PlaybackTrack? = null,
    val queue: List<PlaybackTrack> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackError: String? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val sleepTimerRemainingMs: Long = 0,
    val audiobookSpeed: Float = 1f,
    val guestDjEnabled: Boolean = false,
    val guestDjAvailable: Boolean = false,
    val guestDjLoading: Boolean = false,
    val isCasting: Boolean = false,
    val castVolume: CastVolumeState = CastVolumeState(),
    val offlinePrefetch: OfflinePrefetchState = OfflinePrefetchState(),
    // During an active music crossfade, the outgoing track and the blend length,
    // so Now Playing can cross-dissolve the artwork in step with the audio.
    val crossfadeFromTrack: PlaybackTrack? = null,
    val crossfadeBlendMs: Long = 0,
) {
    val hasPrevious: Boolean get() = canSkipPrevious || positionMs > 3000
    val hasNext: Boolean get() = canSkipNext
}

fun LibraryItem.toPlaybackTrack(): PlaybackTrack = PlaybackTrack(
    id = id,
    title = title,
    artist = subtitle,
    album = album,
    imageUrl = imageUrl,
    durationMs = durationMs,
    playbackPositionMs = playbackPositionMs,
    contentKind = contentKind,
    container = container,
)
