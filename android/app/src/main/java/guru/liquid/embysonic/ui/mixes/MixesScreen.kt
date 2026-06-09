package guru.liquid.embysonic.ui.mixes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.ui.library.CardGrid
import guru.liquid.embysonic.ui.library.CollectionList
import guru.liquid.embysonic.ui.library.StateContent
import guru.liquid.embysonic.ui.library.ViewToggleAction

/**
 * The Mixes destination. Hosts two tabs: Playlists (the user's Emby playlists,
 * browseable now) and Mixes (auto-curated sonic mixes, wired in M4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixesScreen(
    contentPadding: PaddingValues = PaddingValues(),
    onOpenItem: (itemId: String, title: String, detailKind: DetailKind) -> Unit = { _, _, _ -> },
    onOpenNowPlaying: () -> Unit = {},
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Playlists", "Mixes")
    val listView by playlistsViewModel.listView.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        topBar = {
            TopAppBar(
                title = { Text("Mixes") },
                actions = {
                    if (selectedTab == 0) {
                        ViewToggleAction(listView = listView, onToggle = playlistsViewModel::toggleListView)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(label) },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> PlaylistsTab(playlistsViewModel, listView, onOpenItem, onOpenNowPlaying)
                    else -> MixesPlaceholder()
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(
    viewModel: PlaylistsViewModel,
    listView: Boolean,
    onOpenItem: (itemId: String, title: String, detailKind: DetailKind) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StateContent(state) { items ->
        val onClick = { item: LibraryItem ->
            onOpenItem(item.id, item.title, DetailKind.PLAYLIST_TRACKS)
        }
        val onPlay = { item: LibraryItem ->
            viewModel.playPlaylist(item)
            onOpenNowPlaying()
        }
        if (listView) {
            CollectionList(items, placeholderBook = false, onItemClick = onClick, onPlayItem = onPlay)
        } else {
            CardGrid(items, placeholderBook = false, onItemClick = onClick, onPlayItem = onPlay)
        }
    }
}

@Composable
private fun MixesPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Sonic mixes — coming in M4", style = MaterialTheme.typography.bodyMedium)
    }
}
