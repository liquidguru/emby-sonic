package guru.liquid.embysonic.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TabState {
    data object Loading : TabState
    data class Data(val items: List<LibraryItem>) : TabState
    data class Error(val message: String) : TabState
}

data class LibraryUiState(
    val artists: TabState = TabState.Loading,
    val albums: TabState = TabState.Loading,
    val tracks: TabState = TabState.Loading,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>(Routes.ARG_LIBRARY_ID).orEmpty()
    val kind: LibraryKind = runCatching {
        LibraryKind.valueOf(savedStateHandle.get<String>(Routes.ARG_KIND) ?: LibraryKind.MUSIC.name)
    }.getOrDefault(LibraryKind.MUSIC)

    /** Tab labels differ by library type: music vs audiobooks. */
    val tabTitles: List<String> = when (kind) {
        LibraryKind.MUSIC -> listOf("Artists", "Albums", "Tracks")
        LibraryKind.AUDIOBOOKS -> listOf("Authors", "Books", "Chapters")
    }

    val title: String = when (kind) {
        LibraryKind.MUSIC -> "Music"
        LibraryKind.AUDIOBOOKS -> "Audiobooks"
    }

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        loadArtists()
        loadAlbums()
        loadTracks()
    }

    fun loadArtists() = load(
        block = { repository.artists(libraryId) },
        onResult = { tab -> _state.update { it.copy(artists = tab) } },
    )

    fun loadAlbums() = load(
        block = { repository.albums(libraryId) },
        onResult = { tab -> _state.update { it.copy(albums = tab) } },
    )

    fun loadTracks() = load(
        block = { repository.tracks(libraryId) },
        onResult = { tab -> _state.update { it.copy(tracks = tab) } },
    )

    private fun load(
        block: suspend () -> List<LibraryItem>,
        onResult: (TabState) -> Unit,
    ) {
        onResult(TabState.Loading)
        viewModelScope.launch {
            runCatching { block() }.fold(
                onSuccess = { onResult(TabState.Data(it)) },
                onFailure = { onResult(TabState.Error(it.message ?: "Failed to load")) },
            )
        }
    }
}
