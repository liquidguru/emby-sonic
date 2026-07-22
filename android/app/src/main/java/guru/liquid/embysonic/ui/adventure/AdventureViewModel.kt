package guru.liquid.embysonic.ui.adventure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.AdventureRequestDto
import guru.liquid.embysonic.data.coordinator.toLibraryItem
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.playlist.PlaylistRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackSource
import guru.liquid.embysonic.playback.PlaybackTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AdventureResult {
    data object Idle : AdventureResult
    data object Loading : AdventureResult
    data class Data(val tracks: List<LibraryItem>) : AdventureResult
    data class Error(val message: String) : AdventureResult
}

data class AdventureUiState(
    val start: LibraryItem? = null,
    val end: LibraryItem? = null,
    val length: Int = 15,
    val result: AdventureResult = AdventureResult.Idle,
)

@HiltViewModel
class AdventureViewModel @Inject constructor(
    private val coordinator: CoordinatorApi,
    private val repository: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(AdventureUiState())
    val state: StateFlow<AdventureUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    // The in-flight journey build, so a repeated Generate (or changed
    // endpoints/length) cancels the previous request instead of racing it.
    private var generateJob: Job? = null

    init {
        // Default the start to whatever's playing, if anything.
        playback.state.value.currentTrack?.let { current ->
            _state.update { it.copy(start = current.toLibraryItem()) }
        }
    }

    fun setStart(item: LibraryItem) = _state.update { it.copy(start = item, result = AdventureResult.Idle) }
    fun setEnd(item: LibraryItem) = _state.update { it.copy(end = item, result = AdventureResult.Idle) }
    fun setLength(length: Int) = _state.update { it.copy(length = length, result = AdventureResult.Idle) }

    fun generate() {
        val s = _state.value
        val start = s.start
        val end = s.end
        if (start == null || end == null) {
            viewModelScope.launch { _messages.send("Pick a start and an end track") }
            return
        }
        _state.update { it.copy(result = AdventureResult.Loading) }
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            runCatching {
                // The user picks a total length; start + end take two slots, so
                // the middle target is length - 2. Over-request from the
                // coordinator so de-duping (multiple copies of the same song)
                // doesn't leave the journey short, then sample back down.
                val targetMiddle = (s.length - 2).coerceAtLeast(1)
                val requestLength = (targetMiddle * 2 + 4).coerceAtMost(MAX_ADVENTURE_REQUEST)
                val raw = coordinator.adventure(
                    AdventureRequestDto(fromId = start.id, toId = end.id, length = requestLength),
                ).tracks.map { it.toLibraryItem() }
                // Bookend with the user's exact start and end, and dedupe the
                // MIDDLE by title+artist (not id) so multiple library copies of a
                // song collapse and the journey reliably ends on the chosen track.
                val startKey = start.dedupeKey()
                val endKey = end.dedupeKey()
                val distinctMiddle = raw
                    .filter { it.dedupeKey() != startKey && it.dedupeKey() != endKey }
                    .distinctBy { it.dedupeKey() }
                // Evenly sample the middle down to the target so it still spans
                // A→B (taking the first N would drop the B-side of the journey).
                val middle = if (distinctMiddle.size <= targetMiddle) {
                    distinctMiddle
                } else {
                    (0 until targetMiddle).map { distinctMiddle[it * distinctMiddle.size / targetMiddle] }
                }
                val ordered = if (startKey == endKey) {
                    listOf(start) + middle
                } else {
                    listOf(start) + middle + listOf(end)
                }
                val art = runCatching { repository.artworkByIds(ordered.map { it.id }) }
                    .getOrDefault(emptyMap())
                ordered.map { t -> art[t.id]?.let { t.copy(imageUrl = it) } ?: t }
            }.fold(
                onSuccess = { tracks ->
                    _state.update {
                        it.copy(
                            result = if (tracks.isEmpty()) {
                                AdventureResult.Error("No journey found between those tracks")
                            } else {
                                AdventureResult.Data(tracks)
                            },
                        )
                    }
                },
                onFailure = {
                    _state.update { st -> st.copy(result = AdventureResult.Error(it.message ?: "Couldn't build the adventure")) }
                },
            )
        }
    }

    fun play() {
        val tracks = (_state.value.result as? AdventureResult.Data)?.tracks ?: return
        playFrom(tracks.firstOrNull() ?: return)
    }

    /** Play the generated journey starting from [item]. */
    fun playFrom(item: LibraryItem) {
        val s = _state.value
        val tracks = (s.result as? AdventureResult.Data)?.tracks ?: return
        if (tracks.none { it.id == item.id }) return
        val label = listOfNotNull(s.start?.title, s.end?.title).joinToString(" → ").ifBlank { "Journey" }
        playback.playQueue(
            tracks,
            item,
            PlaybackSource("adventure:${s.start?.id}:${s.end?.id}", "Sonic Adventure", label, tracks.firstOrNull()?.imageUrl),
        )
        viewModelScope.launch { _openNowPlaying.send(Unit) }
    }

    fun saveAsPlaylist(name: String) {
        val tracks = (_state.value.result as? AdventureResult.Data)?.tracks ?: return
        viewModelScope.launch {
            runCatching { playlists.createPlaylist(name.ifBlank { "Sonic Adventure" }, tracks.map { it.id }) }.fold(
                onSuccess = { _messages.send("Saved $it tracks to Playlists") },
                onFailure = { _messages.send(it.message ?: "Couldn't save playlist") },
            )
        }
    }

    /** Key that treats different library copies of the same song as one. */
    private fun LibraryItem.dedupeKey(): String {
        val t = title.trim().lowercase()
        val a = subtitle?.trim()?.lowercase().orEmpty()
        return if (t.isBlank()) id else "$t|$a"
    }

    private fun PlaybackTrack.toLibraryItem(): LibraryItem = LibraryItem(
        id = id,
        title = title,
        subtitle = artist,
        imageUrl = imageUrl,
        album = album,
        durationMs = durationMs,
        // Carry the source container: when an adventure is seeded from the
        // currently-playing track, a dropped container left the start bookend
        // looking like direct-play, so the crossfade gate armed against a
        // transcoded WMA and looped it (the Heavy/Original bug).
        container = container,
        contentKind = contentKind,
    )

    private companion object {
        // Cap on how many interpolation steps we ask the coordinator for.
        const val MAX_ADVENTURE_REQUEST = 60
    }
}
