package guru.liquid.embysonic.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.resumeStartItem
import guru.liquid.embysonic.data.playlist.PlaylistRepository
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.playbackSourceFor
import guru.liquid.embysonic.ui.nav.Routes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TabState {
    data object Idle : TabState
    data object Loading : TabState
    data class Data(val items: List<LibraryItem>) : TabState
    data class Error(val message: String) : TabState
}

data class LibraryUiState(
    val artists: TabState = TabState.Idle,
    val albums: TabState = TabState.Idle,
    val genres: TabState = TabState.Idle,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Card grid vs. list, persisted and shared across library/detail screens. */
    val listView: StateFlow<Boolean> =
        settings.libraryListView.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleListView() = viewModelScope.launch { settings.setLibraryListView(!listView.value) }

    private val libraryId: String = savedStateHandle.get<String>(Routes.ARG_LIBRARY_ID).orEmpty()
    val kind: LibraryKind = runCatching {
        LibraryKind.valueOf(savedStateHandle.get<String>(Routes.ARG_KIND) ?: LibraryKind.MUSIC.name)
    }.getOrDefault(LibraryKind.MUSIC)

    /**
     * Two entry tabs only. Tracks/chapters are no longer flat tabs — they live
     * inside a drill-down (Artist→Albums→Tracks, Author→Books→Chapters).
     */
    val tabTitles: List<String> = when (kind) {
        LibraryKind.MUSIC -> listOf("Artists", "Albums", "Genres")
        LibraryKind.AUDIOBOOKS -> listOf("Authors", "Books")
    }

    val title: String = when (kind) {
        LibraryKind.MUSIC -> "Music"
        LibraryKind.AUDIOBOOKS -> "Audiobooks"
    }

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        loadTab(0)
    }

    fun loadTab(index: Int) {
        val current = when (index) {
            0 -> _state.value.artists
            1 -> _state.value.albums
            else -> _state.value.genres
        }
        if (current is TabState.Loading || current is TabState.Data) return
        when (index) {
            0 -> loadArtists()
            1 -> loadAlbums()
            else -> if (kind == LibraryKind.MUSIC) loadGenres()
        }
    }

    fun loadArtists() {
        val audiobooks = kind == LibraryKind.AUDIOBOOKS
        load(
            cacheKey = if (audiobooks) repository.authorsCacheKey(libraryId)
            else repository.artistsCacheKey(libraryId),
            // Audiobook authors need cover resolution from their chapters too.
            fetch = { force ->
                if (audiobooks) repository.authors(libraryId, forceRefresh = force)
                else repository.artists(libraryId, forceRefresh = force)
            },
            onResult = { tab -> _state.update { it.copy(artists = tab) } },
        )
    }

    fun loadAlbums() {
        val audiobooks = kind == LibraryKind.AUDIOBOOKS
        load(
            cacheKey = if (audiobooks) repository.booksCacheKey(libraryId)
            else repository.albumsCacheKey(libraryId),
            // Audiobook "books" need cover resolution from their child chapters.
            fetch = { force ->
                if (audiobooks) repository.books(parentId = libraryId, forceRefresh = force)
                else repository.albums(libraryId, forceRefresh = force)
            },
            onResult = { tab -> _state.update { it.copy(albums = tab) } },
        )
    }

    fun loadGenres() = load(
        cacheKey = repository.genresCacheKey(libraryId),
        fetch = { force -> repository.genres(libraryId, forceRefresh = force) },
        onResult = { tab -> _state.update { it.copy(genres = tab) } },
    )

    /**
     * Stale-while-revalidate: show the cached list instantly (persisted across app
     * restarts), then refresh once per session in the background and update. Falls
     * back to a plain blocking load when nothing is cached.
     */
    private fun load(
        cacheKey: String,
        fetch: suspend (forceRefresh: Boolean) -> List<LibraryItem>,
        onResult: (TabState) -> Unit,
    ) {
        viewModelScope.launch {
            val cached = runCatching { repository.peekBrowse(cacheKey) }.getOrNull()
            if (cached != null) {
                onResult(TabState.Data(cached))
                if (repository.shouldRevalidate(cacheKey)) {
                    runCatching { fetch(true) }.onSuccess { onResult(TabState.Data(it)) }
                }
            } else {
                onResult(TabState.Loading)
                runCatching { fetch(false) }.fold(
                    onSuccess = {
                        repository.shouldRevalidate(cacheKey) // mark refreshed this session
                        onResult(TabState.Data(it))
                    },
                    onFailure = { onResult(TabState.Error(it.message ?: "Failed to load")) },
                )
            }
        }
    }

    // ---- Manual multi-select → playlist (music only) ---------------------------

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    fun enterSelection() { _selectionMode.value = true }

    fun exitSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelected(id: String) =
        _selectedIds.update { if (id in it) it - id else it + id }

    /**
     * Gathers the tracks of every selected collection and saves them as an Emby
     * playlist. [areAlbums] true → the selection is albums (scope by ParentId);
     * false → artists (scope by AlbumArtistIds).
     */
    fun createPlaylistFromSelection(name: String, areAlbums: Boolean) {
        val collectionIds = _selectedIds.value.toList()
        if (collectionIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val trackIds = playlists.trackIdsForCollections(collectionIds, areAlbums)
                playlists.createPlaylist(name, trackIds)
            }.fold(
                onSuccess = {
                    _messages.send("Saved \"$name\" to Emby ($it tracks)")
                    exitSelection()
                },
                onFailure = { _messages.send("Couldn't create playlist: ${it.message}") },
            )
        }
    }

    fun playCollection(item: LibraryItem, detailKind: guru.liquid.embysonic.data.emby.DetailKind) {
        viewModelScope.launch {
            runCatching { repository.playableItems(item.id, detailKind) }.fold(
                onSuccess = { items ->
                    val first = if (detailKind == guru.liquid.embysonic.data.emby.DetailKind.BOOK_CHAPTERS) {
                        items.resumeStartItem()
                    } else {
                        items.firstOrNull()
                    }
                    if (first == null) {
                        _messages.send("Nothing playable in \"${item.title}\"")
                    } else {
                        playback.playQueue(items, first, playbackSourceFor(detailKind, item))
                        _openNowPlaying.send(Unit)
                    }
                },
                onFailure = { _messages.send("Couldn't start playback: ${it.message}") },
            )
        }
    }

    fun shuffleCollection(item: LibraryItem, detailKind: guru.liquid.embysonic.data.emby.DetailKind) {
        viewModelScope.launch {
            runCatching { repository.playableItems(item.id, detailKind) }.fold(
                onSuccess = { items ->
                    if (items.isEmpty()) {
                        _messages.send("Nothing playable in \"${item.title}\"")
                    } else {
                        playback.prepareShuffledQueue(items, playbackSourceFor(detailKind, item))
                    }
                },
                onFailure = { _messages.send("Couldn't shuffle: ${it.message}") },
            )
        }
    }
}
