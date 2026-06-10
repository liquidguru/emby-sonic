package guru.liquid.embysonic.ui.mixes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlin.math.abs
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
    val mixOptions by playlistsViewModel.mixOptions.collectAsStateWithLifecycle()
    var showMixOptions by rememberSaveable { mutableStateOf(false) }
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
                        selectedMix?.displayTitle() ?: "Mixes",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (selectedTab == 0) {
                        ViewToggleAction(listView = listView, onToggle = playlistsViewModel::toggleListView)
                    } else if (selectedMix == null) {
                        IconButton(onClick = { showMixOptions = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "Mix options")
                        }
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
                        onSaveMix = playlistsViewModel::saveSonicMixAsPlaylist,
                        onRegenerateMix = { n -> playlistsViewModel.regenerateSonicMix(n) },
                        message = mixOptions.message,
                        regenerating = mixOptions.generating,
                    )
                }
            }
        }
    }
    if (showMixOptions) {
        MixOptionsSheet(
            options = mixOptions,
            onTracksPerMixChange = playlistsViewModel::setTracksPerMix,
            onGenerate = playlistsViewModel::generateSonicMixes,
            onDismiss = { showMixOptions = false },
        )
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
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleteTarget = (state as? guru.liquid.embysonic.ui.library.TabState.Data)
        ?.items?.firstOrNull { it.id == deleteTargetId }

    StateContent(state) { items ->
        val onClick = { item: LibraryItem ->
            onOpenItem(item.id, item.title, DetailKind.PLAYLIST_TRACKS)
        }
        val onPlay = { item: LibraryItem ->
            viewModel.playPlaylist(item)
            onOpenNowPlaying()
        }
        val onDelete = { item: LibraryItem -> deleteTargetId = item.id }
        if (listView) {
            CollectionList(items, placeholderBook = false, onItemClick = onClick, onPlayItem = onPlay, onDeleteItem = onDelete)
        } else {
            CardGrid(items, placeholderBook = false, onItemClick = onClick, onPlayItem = onPlay, onDeleteItem = onDelete)
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text("Delete playlist") },
            text = { Text("Delete \"${deleteTarget.title}\" from Emby? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(deleteTarget)
                        deleteTargetId = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SonicMixesTab(
    state: SonicMixesState,
    onOpenMix: (SonicMixDto) -> Unit,
    onPlayMix: (SonicMixDto) -> Unit,
    onPlayTracks: (tracks: List<LibraryItem>, start: LibraryItem) -> Unit,
    onSaveMix: (name: String, tracks: List<LibraryItem>) -> Unit,
    onRegenerateMix: (tracksPerMix: Int) -> Unit,
    message: String?,
    regenerating: Boolean,
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
            onSaveMix = onSaveMix,
            onRegenerateMix = { n -> onRegenerateMix(n) },
            message = message,
            regenerating = regenerating,
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
                        mix.displayTitle(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        mix.displayMeta(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPlayMix(mix) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${mix.displayTitle()}")
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
    onSaveMix: (name: String, tracks: List<LibraryItem>) -> Unit,
    onRegenerateMix: (tracksPerMix: Int) -> Unit,
    message: String?,
    regenerating: Boolean,
) {
    if (tracks.isEmpty()) {
        CenterMessage { Text("No tracks in this mix") }
        return
    }
    var saveDialogOpen by rememberSaveable(mix.id) { mutableStateOf(false) }
    var refreshDialogOpen by rememberSaveable(mix.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MixIcon(modifier = Modifier.size(64.dp))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    mix.displayTitle(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    mix.displayMeta(trackCount = tracks.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = { onPlayTracks(tracks, tracks.first()) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play mix")
            }
        }
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { saveDialogOpen = true },
                modifier = Modifier.widthIn(min = 160.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                Text("Save as playlist", modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(
                onClick = { refreshDialogOpen = true },
                enabled = !regenerating,
            ) {
                if (regenerating) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
                Text("Refresh", modifier = Modifier.padding(start = 8.dp))
            }
        }
        message?.let {
            AssistChip(
                onClick = {},
                label = { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        TrackList(
            items = tracks,
            placeholderBook = false,
            onTrackClick = { track -> onPlayTracks(tracks, track) },
        )
    }
    if (saveDialogOpen) {
        SaveMixDialog(
            initialName = mix.displayTitle(),
            onDismiss = { saveDialogOpen = false },
            onSave = { name ->
                saveDialogOpen = false
                onSaveMix(name, tracks)
            },
        )
    }
    if (refreshDialogOpen) {
        RefreshMixDialog(
            currentTrackCount = tracks.size,
            onDismiss = { refreshDialogOpen = false },
            onRefresh = { tracksPerMix ->
                refreshDialogOpen = false
                onRegenerateMix(tracksPerMix)
            },
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
private fun SaveMixDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Playlist name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RefreshMixDialog(
    currentTrackCount: Int,
    onDismiss: () -> Unit,
    onRefresh: (tracksPerMix: Int) -> Unit,
) {
    var tracksPerMix by rememberSaveable { mutableStateOf(
        listOf(25, 50, 75, 100).minByOrNull { abs(it - currentTrackCount) } ?: 50
    ) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Refresh mix") },
        text = {
            Column {
                Text(
                    "Pick up new tracks added since the last full build. How many tracks?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.padding(top = 16.dp)) {
                    listOf(25, 50, 75, 100).forEach { count ->
                        FilterChip(
                            selected = tracksPerMix == count,
                            onClick = { tracksPerMix = count },
                            label = { Text(count.toString()) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRefresh(tracksPerMix) }) {
                Text("Refresh")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixOptionsSheet(
    options: SonicMixOptions,
    onTracksPerMixChange: (Int) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text("Mix options", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Tracks per mix",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
            Row(modifier = Modifier.padding(top = 10.dp)) {
                listOf(25, 50, 75, 100).forEach { count ->
                    FilterChip(
                        selected = options.tracksPerMix == count,
                        onClick = { onTracksPerMixChange(count) },
                        label = { Text(count.toString()) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onGenerate,
                    enabled = !options.generating,
                ) {
                    Text("Generate mixes")
                }
                if (options.generating) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(start = 16.dp).size(24.dp),
                    )
                }
            }
            options.message?.let {
                AssistChip(
                    onClick = {},
                    label = { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
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

private fun SonicMixDto.displayMeta(trackCount: Int = this.trackCount): String {
    val mixNumber = clusterId?.let { "Mix ${it + 1}" }
    val count = "$trackCount tracks"
    return listOfNotNull(mixNumber, count).joinToString(" • ")
}
