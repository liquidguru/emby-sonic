package guru.liquid.embysonic.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.resumeStartItem
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.playback.PlaybackController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val userName: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val resumeAudiobooks: List<LibraryItem> = emptyList(),
    val playlists: List<LibraryItem> = emptyList(),
    val recentAlbums: List<LibraryItem> = emptyList(),
    val artists: List<LibraryItem> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    init {
        val snap = settings.snapshot()
        _state.update { it.copy(userName = snap.userName) }
        refresh()
    }

    fun refresh(showLoading: Boolean = true) {
        _state.update {
            if (showLoading) {
                it.copy(loading = true, error = null)
            } else {
                it.copy(error = null)
            }
        }
        viewModelScope.launch {
            runCatching {
                val libraries = repository.audioLibraries()
                val musicLibrary = libraries.firstOrNull { it.kind == LibraryKind.MUSIC }
                val audiobookLibrary = libraries.firstOrNull { it.kind == LibraryKind.AUDIOBOOKS }
                val resumeAudiobooks = audiobookLibrary
                    ?.let { repository.resumeAudiobooks(it.id, HOME_SECTION_LIMIT) }
                    .orEmpty()
                val playlists = repository.playlists().take(HOME_SECTION_LIMIT)
                val albums = musicLibrary
                    ?.let { repository.recentlyAddedAlbums(it.id, HOME_SECTION_LIMIT) }
                    .orEmpty()
                val artists = musicLibrary
                    ?.let { repository.artists(it.id).take(HOME_SECTION_LIMIT) }
                    .orEmpty()
                HomeUiState(
                    userName = settings.snapshot().userName,
                    loading = false,
                    resumeAudiobooks = resumeAudiobooks,
                    playlists = playlists,
                    recentAlbums = albums,
                    artists = artists,
                )
            }.fold(
                onSuccess = { next -> _state.value = next },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: "Could not load Home",
                        )
                    }
                },
            )
        }
    }

    fun playPlaylist(item: LibraryItem) = playCollection(item, DetailKind.PLAYLIST_TRACKS)

    fun playAlbum(item: LibraryItem) = playCollection(item, DetailKind.ALBUM_TRACKS)

    fun playArtist(item: LibraryItem) = playCollection(item, DetailKind.ARTIST_ALBUMS)

    fun playResumeAudiobook(item: LibraryItem) = playCollection(item, DetailKind.BOOK_CHAPTERS)

    private fun playCollection(item: LibraryItem, detailKind: DetailKind) {
        viewModelScope.launch {
            runCatching { repository.playableItems(item.id, detailKind) }.fold(
                onSuccess = { items ->
                    val first = if (detailKind == DetailKind.BOOK_CHAPTERS) {
                        items.resumeStartItem()
                    } else {
                        items.firstOrNull()
                    }
                    if (first == null) {
                        _messages.send("Nothing playable in \"${item.title}\"")
                    } else {
                        playback.playQueue(items, first)
                        _openNowPlaying.send(Unit)
                    }
                },
                onFailure = { _messages.send("Couldn't start playback: ${it.message}") },
            )
        }
    }

    private companion object {
        const val HOME_SECTION_LIMIT = 12
    }
}
