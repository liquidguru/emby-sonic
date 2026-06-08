package guru.liquid.embysonic.playback

import guru.liquid.embysonic.data.emby.LibraryItem

data class PlaybackTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val imageUrl: String?,
    val durationMs: Long?,
)

data class PlaybackUiState(
    val currentTrack: PlaybackTrack? = null,
    val queue: List<PlaybackTrack> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
) {
    val hasPrevious: Boolean get() = currentIndex > 0
    val hasNext: Boolean get() = currentIndex < queue.lastIndex
}

fun LibraryItem.toPlaybackTrack(): PlaybackTrack = PlaybackTrack(
    id = id,
    title = title,
    artist = subtitle,
    album = album,
    imageUrl = imageUrl,
    durationMs = durationMs,
)
