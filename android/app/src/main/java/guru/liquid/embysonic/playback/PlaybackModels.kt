package guru.liquid.embysonic.playback

import guru.liquid.embysonic.data.emby.ContentKind
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
)

enum class PlaybackRepeatMode {
    OFF,
    ALL,
    ONE,
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
)
