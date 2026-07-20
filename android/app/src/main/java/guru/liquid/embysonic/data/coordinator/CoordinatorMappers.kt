package guru.liquid.embysonic.data.coordinator

import guru.liquid.embysonic.data.coordinator.dto.TrackOutDto
import guru.liquid.embysonic.data.emby.ContentKind
import guru.liquid.embysonic.data.emby.LibraryItem

/**
 * Maps a coordinator track (sonic mixes / radio / similar) to a [LibraryItem].
 * Artwork is left null here — coordinator responses carry no images — and is
 * hydrated separately via `LibraryRepository.artworkByIds`.
 */
fun TrackOutDto.toLibraryItem(): LibraryItem = LibraryItem(
    id = id,
    title = title.orEmpty().ifBlank { "Unknown track" },
    subtitle = artist,
    imageUrl = null,
    trailingText = formatTrackDuration(durationMs),
    album = album,
    durationMs = durationMs,
    // Sonic features (mixes/radio/adventure/similar) are music-only — the
    // coordinator excludes spoken-word/audiobooks from analysis.
    contentKind = ContentKind.MUSIC,
    container = container,
)

private fun formatTrackDuration(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, remainingMinutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
