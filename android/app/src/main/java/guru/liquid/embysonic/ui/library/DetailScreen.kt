package guru.liquid.embysonic.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem

/**
 * One drill-down level. Grid kinds (artist/author) show tappable albums/books and
 * call [onOpenItem] to push the next level; leaf kinds (album/book) show a
 * track/chapter list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    contentPadding: PaddingValues = PaddingValues(),
    onBack: () -> Unit = {},
    onOpenItem: (itemId: String, title: String, detailKind: DetailKind) -> Unit = { _, _, _ -> },
    onOpenNowPlaying: () -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listView by viewModel.listView.collectAsStateWithLifecycle()
    val kind = viewModel.kind
    val snackbarHostState = remember { SnackbarHostState() }
    val hasPlayableItems = (state as? TabState.Data)?.items?.isNotEmpty() == true
    val canShuffle = kind == DetailKind.ALBUM_TRACKS || kind == DetailKind.PLAYLIST_TRACKS

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Sonic playlist actions belong to music tracks only, not audiobook chapters.
    val trackActions = if (kind == DetailKind.ALBUM_TRACKS) {
        listOf(
            TrackAction("More like this", viewModel::createSimilarPlaylist),
            TrackAction("Start radio", viewModel::createRadioPlaylist),
        )
    } else {
        emptyList()
    }

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(viewModel.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    // The toggle only applies to grid levels (albums/books), not leaf lists.
                    if (kind.isGrid) {
                        ViewToggleAction(listView = listView, onToggle = viewModel::toggleListView)
                    } else if (hasPlayableItems) {
                        IconButton(
                            onClick = {
                                viewModel.playFirst()
                                onOpenNowPlaying()
                            },
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                        }
                        if (canShuffle) {
                            IconButton(
                                onClick = {
                                    viewModel.shuffleAll()
                                },
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            StateContent(state) { items ->
                if (kind.isGrid) {
                    val onClick = { item: LibraryItem ->
                        kind.childKind?.let { child -> onOpenItem(item.id, item.title, child) }
                        Unit
                    }
                    val onPlay = { item: LibraryItem ->
                        viewModel.playCollection(item)
                        onOpenNowPlaying()
                    }
                    if (listView) {
                        CollectionList(
                            items,
                            placeholderBook = kind.usesBookIcon,
                            onItemClick = onClick,
                            onPlayItem = onPlay,
                        )
                    } else {
                        CardGrid(
                            items,
                            placeholderBook = kind.usesBookIcon,
                            onItemClick = onClick,
                            onPlayItem = onPlay,
                        )
                    }
                } else {
                    TrackList(
                        items = items,
                        placeholderBook = kind.usesBookIcon,
                        actions = trackActions,
                        onTrackClick = {
                            viewModel.playFrom(it)
                            onOpenNowPlaying()
                        },
                    )
                }
            }
        }
    }
}
