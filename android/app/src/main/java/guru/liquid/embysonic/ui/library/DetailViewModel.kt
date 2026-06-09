package guru.liquid.embysonic.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.playlist.PlaylistRepository
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.playback.PlaybackController
import kotlinx.coroutines.channels.Channel
import guru.liquid.embysonic.ui.nav.Routes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs a single drill-down level: an artist's albums, an album's tracks, an
 * author's books, or a book's chapters — selected by [DetailKind].
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>(Routes.ARG_ITEM_ID).orEmpty()
    val kind: DetailKind = runCatching {
        DetailKind.valueOf(savedStateHandle.get<String>(Routes.ARG_DETAIL_KIND).orEmpty())
    }.getOrDefault(DetailKind.ALBUM_TRACKS)
    val title: String = savedStateHandle.get<String>(Routes.ARG_TITLE).orEmpty()

    /** Card grid vs. list, persisted and shared across library/detail screens. */
    val listView: StateFlow<Boolean> =
        settings.libraryListView.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleListView() = viewModelScope.launch { settings.setLibraryListView(!listView.value) }

    private val _state = MutableStateFlow<TabState>(TabState.Loading)
    val state: StateFlow<TabState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = TabState.Loading
        viewModelScope.launch {
            runCatching { repository.childItems(itemId, kind) }.fold(
                onSuccess = { _state.value = TabState.Data(it) },
                onFailure = { _state.value = TabState.Error(it.message ?: "Failed to load") },
            )
        }
    }

    // Transient one-shot messages (playlist created / failed) for a snackbar.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Sonic "more like this": seed track + its nearest neighbours → Emby playlist. */
    fun createSimilarPlaylist(seed: LibraryItem) = generate(
        name = "Similar to ${seed.title}",
        build = { playlists.similarTrackIds(seed.id) },
    )

    /** Sonic radio: a longer seeded sequence → Emby playlist. */
    fun createRadioPlaylist(seed: LibraryItem) = generate(
        name = "${seed.title} Radio",
        build = { playlists.radioTrackIds(seed.id) },
    )

    fun playFrom(seed: LibraryItem) {
        val items = (state.value as? TabState.Data)?.items.orEmpty().ifEmpty { listOf(seed) }
        playback.playQueue(items, seed)
    }

    fun playFirst() {
        val items = (state.value as? TabState.Data)?.items.orEmpty()
        val seed = items.firstOrNull() ?: return
        playback.playQueue(items, seed)
    }

    fun shuffleAll() {
        val items = (state.value as? TabState.Data)?.items.orEmpty()
        val seed = items.randomOrNull() ?: return
        playback.playQueue(items, seed, shuffled = true)
    }

    private fun generate(name: String, build: suspend () -> List<String>) {
        viewModelScope.launch {
            runCatching { playlists.createPlaylist(name, build()) }.fold(
                onSuccess = { _messages.send("Saved \"$name\" to Emby ($it tracks)") },
                onFailure = { _messages.send("Couldn't create playlist: ${it.message}") },
            )
        }
    }
}
