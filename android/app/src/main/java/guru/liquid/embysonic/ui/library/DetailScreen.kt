package guru.liquid.embysonic.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val similarState by viewModel.similarState.collectAsStateWithLifecycle()
    val listView by viewModel.listView.collectAsStateWithLifecycle()
    val genreTracksPerMix by viewModel.genreTracksPerMix.collectAsStateWithLifecycle()
    val kind = viewModel.kind
    val snackbarHostState = remember { SnackbarHostState() }
    val hasPlayableItems = (state as? TabState.Data)?.items?.isNotEmpty() == true
    val canShuffle = kind == DetailKind.ALBUM_TRACKS ||
        kind == DetailKind.GENRE_TRACKS ||
        kind == DetailKind.PLAYLIST_TRACKS
    val isGenreMix = kind == DetailKind.GENRE_TRACKS
    var saveDialogOpen by rememberSaveable { mutableStateOf(false) }
    var refreshDialogOpen by rememberSaveable { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<LibraryItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.openNowPlaying.collect { onOpenNowPlaying() }
    }

    // Sonic playlist actions belong to music tracks only, not audiobook chapters.
    val trackActions = when (kind) {
        DetailKind.ALBUM_TRACKS, DetailKind.GENRE_TRACKS -> listOf(
            TrackAction("More like this", viewModel::createSimilarPlaylist),
            TrackAction("Start radio", viewModel::createRadioPlaylist),
        )
        DetailKind.PLAYLIST_TRACKS -> listOf(
            TrackAction("Remove from playlist") { removeTarget = it },
        )
        DetailKind.ARTIST_ALBUMS, DetailKind.AUTHOR_BOOKS, DetailKind.BOOK_CHAPTERS -> emptyList()
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
                        if (isGenreMix) {
                            IconButton(onClick = { refreshDialogOpen = true }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh genre mix")
                            }
                            IconButton(onClick = { saveDialogOpen = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "Save as playlist",
                                )
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
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        SimilarCollectionsRail(
                            state = similarState,
                            onOpenItem = { item, targetKind -> onOpenItem(item.id, item.title, targetKind) },
                        )
                        if (listView) {
                            CollectionList(
                                items,
                                placeholderBook = kind.usesBookIcon,
                                onItemClick = onClick,
                                modifier = Modifier.weight(1f),
                                onPlayItem = onPlay,
                            )
                        } else {
                            CardGrid(
                                items,
                                placeholderBook = kind.usesBookIcon,
                                onItemClick = onClick,
                                modifier = Modifier.weight(1f),
                                onPlayItem = onPlay,
                            )
                        }
                    }
                } else {
                    if (isGenreMix) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, bottom = 8.dp)) {
                                TextButton(onClick = { saveDialogOpen = true }) {
                                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                                    Text("Save as playlist", modifier = Modifier.padding(start = 8.dp))
                                }
                                TextButton(onClick = { refreshDialogOpen = true }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Text("Refresh $genreTracksPerMix", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                            TrackList(
                                items = items,
                                placeholderBook = kind.usesBookIcon,
                                modifier = Modifier.weight(1f),
                                actions = trackActions,
                                onTrackClick = {
                                    viewModel.playFrom(it)
                                },
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            SimilarCollectionsRail(
                                state = similarState,
                                onOpenItem = { item, targetKind -> onOpenItem(item.id, item.title, targetKind) },
                            )
                            TrackList(
                                items = items,
                                placeholderBook = kind.usesBookIcon,
                                modifier = Modifier.weight(1f),
                                actions = trackActions,
                                onTrackClick = {
                                    viewModel.playFrom(it)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (refreshDialogOpen) {
        GenreMixRefreshDialog(
            tracksPerMix = genreTracksPerMix,
            onTracksPerMixChange = viewModel::setGenreTracksPerMix,
            onDismiss = { refreshDialogOpen = false },
            onRefresh = {
                refreshDialogOpen = false
                viewModel.load()
            },
        )
    }
    if (saveDialogOpen) {
        SaveGenreMixDialog(
            initialName = "${viewModel.title} genre mix",
            onDismiss = { saveDialogOpen = false },
            onSave = { name ->
                saveDialogOpen = false
                viewModel.saveCurrentAsPlaylist(name)
            },
        )
    }
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove from playlist") },
            text = { Text("Remove \"${target.title}\" from this playlist? The song stays in your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeTarget = null
                        viewModel.removePlaylistItem(target)
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SimilarCollectionsRail(
    state: SimilarCollectionsState,
    onOpenItem: (LibraryItem, DetailKind) -> Unit,
) {
    when (state) {
        SimilarCollectionsState.Hidden -> Unit
        is SimilarCollectionsState.Loading -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        is SimilarCollectionsState.Error -> {
            Text(
                state.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        is SimilarCollectionsState.Data -> {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        SimilarCollectionCard(
                            item = item,
                            onClick = { onOpenItem(item, state.targetKind) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarCollectionCard(item: LibraryItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Artwork(
            url = item.imageUrl,
            contentDescription = item.title,
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            item.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun GenreMixRefreshDialog(
    tracksPerMix: Int,
    onTracksPerMixChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Refresh genre mix") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("Pick how many random tracks from this genre.")
                androidx.compose.foundation.layout.Row(modifier = Modifier.padding(top = 16.dp)) {
                    GenreMixTrackCounts.forEach { count ->
                        FilterChip(
                            selected = tracksPerMix == count,
                            onClick = { onTracksPerMixChange(count) },
                            label = { Text(count.toString()) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) { Text("Refresh") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SaveGenreMixDialog(
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
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
