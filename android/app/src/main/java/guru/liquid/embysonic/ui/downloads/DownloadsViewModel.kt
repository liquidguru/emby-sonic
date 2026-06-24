package guru.liquid.embysonic.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.download.DownloadIndex
import guru.liquid.embysonic.data.download.DownloadStore
import guru.liquid.embysonic.data.download.DownloadedPlaylist
import guru.liquid.embysonic.data.download.PlaylistDownloader
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Browses and plays downloaded playlists — works with no network. */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadStore: DownloadStore,
    private val downloader: PlaylistDownloader,
    private val playback: PlaybackController,
) : ViewModel() {

    val state: StateFlow<DownloadIndex> =
        downloadStore.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), downloadStore.state.value)

    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    /** Play the downloaded tracks of a playlist from local files. */
    fun play(playlist: DownloadedPlaylist) {
        val items = downloadStore.playableItems(playlist.playlistId)
        if (items.isEmpty()) return
        playback.playQueue(
            items = items,
            startItem = items.first(),
            source = PlaybackSource(
                key = "downloaded:${playlist.playlistId}",
                title = playlist.name,
                subtitle = "Downloaded",
                coverUrl = playlist.coverUrl,
            ),
        )
        viewModelScope.launch { _openNowPlaying.send(Unit) }
    }

    /** Delete a playlist's downloaded files (keeps the Emby playlist). */
    fun remove(playlistId: String) {
        viewModelScope.launch {
            downloader.cancel(playlistId)
            downloadStore.removePlaylist(playlistId)
        }
    }

    /** Local cover-art URI string for a playlist's first track that has one. */
    fun coverModel(playlist: DownloadedPlaylist): String? =
        playlist.tracks.firstNotNullOfOrNull { downloadStore.artUri(it) }?.toString()
}
