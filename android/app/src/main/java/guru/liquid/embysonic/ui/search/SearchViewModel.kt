package guru.liquid.embysonic.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.emby.AudioLibrary
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a search query matches against. */
enum class SearchScope { TRACKS, ALBUMS, ARTISTS, BOOKS, AUTHORS }

/** Which set of scopes a search screen offers, and where it starts. */
enum class SearchMode(val scopes: List<SearchScope>, val initial: SearchScope) {
    MUSIC(listOf(SearchScope.TRACKS, SearchScope.ALBUMS, SearchScope.ARTISTS), SearchScope.TRACKS),
    AUDIOBOOKS(listOf(SearchScope.BOOKS, SearchScope.AUTHORS), SearchScope.BOOKS),
    ALL(
        listOf(SearchScope.TRACKS, SearchScope.ALBUMS, SearchScope.ARTISTS, SearchScope.BOOKS, SearchScope.AUTHORS),
        SearchScope.TRACKS,
    ),
}

sealed interface SearchResults {
    data object Empty : SearchResults
    data object Loading : SearchResults
    data class Data(val items: List<LibraryItem>) : SearchResults
    data class Error(val message: String) : SearchResults
}

/** Debounced search across music and audiobook libraries; reused by the library Search screens and the Adventure picker. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val playback: PlaybackController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val mode: SearchMode = runCatching {
        SearchMode.valueOf(savedStateHandle.get<String>(ARG_MODE) ?: SearchMode.MUSIC.name)
    }.getOrDefault(SearchMode.MUSIC)

    val scopes: List<SearchScope> = mode.scopes

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _scope = MutableStateFlow(mode.initial)
    val scope: StateFlow<SearchScope> = _scope.asStateFlow()

    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    // Resolve library ids once so each scope queries the right library.
    private var libraries: List<AudioLibrary>? = null

    private val debouncedQuery = _query.debounce(300).map { it.trim() }.distinctUntilChanged()

    val results: StateFlow<SearchResults> =
        combine(debouncedQuery, _scope) { q, scope -> q to scope }
            .flatMapLatest { (q, scope) ->
                flow {
                    if (q.length < MIN_QUERY) {
                        emit(SearchResults.Empty)
                        return@flow
                    }
                    emit(SearchResults.Loading)
                    emit(
                        runCatching { search(q, scope) }.fold(
                            onSuccess = { SearchResults.Data(it) },
                            onFailure = { SearchResults.Error(it.message ?: "Search failed") },
                        ),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults.Empty)

    private suspend fun search(q: String, scope: SearchScope): List<LibraryItem> {
        val parentId = parentIdFor(scope)
        return when (scope) {
            SearchScope.TRACKS -> searchTracksExpanded(q, parentId)
            SearchScope.ALBUMS -> repository.searchAlbums(q, parentId)
            SearchScope.ARTISTS -> repository.searchArtists(q, parentId)
            SearchScope.BOOKS -> repository.searchBooks(q, parentId)
            SearchScope.AUTHORS -> repository.searchAuthors(q, parentId)
        }
    }

    // Direct track matches + all tracks by any artist whose name matches, deduped.
    private suspend fun searchTracksExpanded(q: String, parentId: String?): List<LibraryItem> =
        coroutineScope {
            val directDeferred = async { repository.searchTracks(q, parentId) }
            val artistsDeferred = async { repository.searchArtists(q, parentId) }
            val direct = directDeferred.await()
            val artistIds = artistsDeferred.await().map { it.id }
            val byArtist = repository.tracksByArtistIds(artistIds, parentId)
            val seen = HashSet<String>(direct.size + byArtist.size)
            (direct + byArtist).filter { seen.add(it.id) }
        }

    private suspend fun parentIdFor(scope: SearchScope): String? {
        val libs = libraries
            ?: repository.audioLibraries().also { libraries = it }
        val kind = when (scope) {
            SearchScope.BOOKS, SearchScope.AUTHORS -> LibraryKind.AUDIOBOOKS
            else -> LibraryKind.MUSIC
        }
        return libs.firstOrNull { it.kind == kind }?.id
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun setScope(value: SearchScope) {
        _scope.value = value
    }

    /** Play a single track result (Tracks scope). */
    fun playTrack(item: LibraryItem) {
        playback.playQueue(
            listOf(item),
            item,
            PlaybackSource("track:${item.id}", item.title, item.subtitle ?: "Song", item.imageUrl),
        )
        viewModelScope.launch { _openNowPlaying.send(Unit) }
    }

    private companion object {
        const val MIN_QUERY = 2
        const val ARG_MODE = "mode"
    }
}
