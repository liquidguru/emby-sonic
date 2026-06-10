package guru.liquid.embysonic.ui.mixes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDto
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.ui.library.CardGrid
import guru.liquid.embysonic.ui.library.CollectionList
import guru.liquid.embysonic.ui.library.StateContent
import guru.liquid.embysonic.ui.library.TrackList
import guru.liquid.embysonic.ui.library.ViewToggleAction

/**
 * The Mixes destination. Hosts two tabs: Playlists (the user's Emby playlists,
 * browseable now) and Mixes (auto-curated sonic mixes from the coordinator).
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
    val sonicState by playlistsViewModel.sonicState.collectAsStateWithLifecycle()
    val selectedMix = (sonicState as? SonicMixesState.DetailLoading)?.mix
        ?: (sonicState as? SonicMixesState.DetailData)?.mix

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selectedTab == 1 && selectedMix != null) {
                        IconButton(onClick = playlistsViewModel::closeSonicMix) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    Text(
                        selectedMix?.name ?: "Mixes",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (selectedTab == 0) {
                        ViewToggleAction(listView = listView, onToggle = playlistsViewModel::toggleListView)
                    } else if (selectedMix == null) {
                        IconButton(onClick = playlistsViewModel::loadSonicMixes) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh mixes")
                        }
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
                    else -> SonicMixesTab(
                        state = sonicState,
                        onOpenMix = playlistsViewModel::openSonicMix,
                        onPlayMix = { mix ->
                            playlistsViewModel.playSonicMix(mix)
                            onOpenNowPlaying()
                        },
                        onPlayTracks = { tracks, start ->
                            playlistsViewModel.playSonicTracks(tracks, start)
                            onOpenNowPlaying()
                        },
                    )
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
private fun SonicMixesTab(
    state: SonicMixesState,
    onOpenMix: (SonicMixDto) -> Unit,
    onPlayMix: (SonicMixDto) -> Unit,
    onPlayTracks: (tracks: List<LibraryItem>, start: LibraryItem) -> Unit,
) {
    when (state) {
        SonicMixesState.Loading -> CenterMessage { CircularProgressIndicator() }
        is SonicMixesState.Error -> CenterMessage {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        is SonicMixesState.ListData -> SonicMixList(
            mixes = state.mixes,
            onOpenMix = onOpenMix,
            onPlayMix = onPlayMix,
        )
        is SonicMixesState.DetailLoading -> CenterMessage {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    state.mix.name ?: "Loading mix",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        is SonicMixesState.DetailData -> SonicMixDetail(
            mix = state.mix,
            tracks = state.tracks,
            onPlayTracks = onPlayTracks,
        )
    }
}

@Composable
private fun SonicMixList(
    mixes: List<SonicMixDto>,
    onOpenMix: (SonicMixDto) -> Unit,
    onPlayMix: (SonicMixDto) -> Unit,
) {
    if (mixes.isEmpty()) {
        CenterMessage { Text("No sonic mixes yet") }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(mixes, key = { it.id }) { mix ->
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { onOpenMix(mix) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { MixIcon() },
                headlineContent = {
                    Text(
                        mix.name ?: "Sonic mix",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        "${mix.trackCount} tracks",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPlayMix(mix) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${mix.name ?: "mix"}")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SonicMixDetail(
    mix: SonicMixDto,
    tracks: List<LibraryItem>,
    onPlayTracks: (tracks: List<LibraryItem>, start: LibraryItem) -> Unit,
) {
    if (tracks.isEmpty()) {
        CenterMessage { Text("No tracks in this mix") }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MixIcon(modifier = Modifier.size(64.dp))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    mix.name ?: "Sonic mix",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${tracks.size} tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = { onPlayTracks(tracks, tracks.first()) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play mix")
            }
        }
        TrackList(
            items = tracks,
            placeholderBook = false,
            onTrackClick = { track -> onPlayTracks(tracks, track) },
        )
    }
}

@Composable
private fun MixIcon(modifier: Modifier = Modifier.size(56.dp)) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
