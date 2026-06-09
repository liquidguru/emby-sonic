package guru.liquid.embysonic.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.ui.library.Artwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenItem: (itemId: String, title: String, detailKind: DetailKind) -> Unit,
    onOpenNowPlaying: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var customizeHome by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.openNowPlaying.collect { onOpenNowPlaying() }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(showLoading = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.userName?.let { "Hi, $it" } ?: "liquidWave") },
                actions = {
                    IconButton(onClick = { customizeHome = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Customize Home")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }

            else -> HomeContent(
                state = state,
                onOpenItem = onOpenItem,
                onPlayPlaylist = viewModel::playPlaylist,
                onPlayAlbum = viewModel::playAlbum,
                onPlayArtist = viewModel::playArtist,
                onPlayResumeAudiobook = viewModel::playResumeAudiobook,
                compactCards = state.compactCards,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }

    if (customizeHome) {
        HomeCustomizeSheet(
            compactCards = state.compactCards,
            sections = state.sectionPreferences,
            onCompactCardsChange = viewModel::setCompactCards,
            onSectionVisibleChange = viewModel::setSectionVisible,
            onMoveSection = viewModel::moveSection,
            onDismiss = { customizeHome = false },
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenItem: (itemId: String, title: String, detailKind: DetailKind) -> Unit,
    onPlayPlaylist: (LibraryItem) -> Unit,
    onPlayAlbum: (LibraryItem) -> Unit,
    onPlayArtist: (LibraryItem) -> Unit,
    onPlayResumeAudiobook: (LibraryItem) -> Unit,
    compactCards: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("liquidWave", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Browse, queue, and keep the music moving.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.sectionPreferences
            .filter { it.visible }
            .forEach { section ->
                val sectionData = when (section.kind) {
                    HomeSectionKind.RESUME_AUDIOBOOKS -> HomeSectionData(
                        items = state.resumeAudiobooks,
                        onClick = { onOpenItem(it.id, it.title, DetailKind.BOOK_CHAPTERS) },
                        onPlay = onPlayResumeAudiobook,
                    )
                    HomeSectionKind.PLAYLISTS -> HomeSectionData(
                        items = state.playlists,
                        onClick = { onOpenItem(it.id, it.title, DetailKind.PLAYLIST_TRACKS) },
                        onPlay = onPlayPlaylist,
                    )
                    HomeSectionKind.RECENT_ALBUMS -> HomeSectionData(
                        items = state.recentAlbums,
                        onClick = { onOpenItem(it.id, it.title, DetailKind.ALBUM_TRACKS) },
                        onPlay = onPlayAlbum,
                    )
                    HomeSectionKind.ARTISTS -> HomeSectionData(
                        items = state.artists,
                        onClick = { onOpenItem(it.id, it.title, DetailKind.ARTIST_ALBUMS) },
                        onPlay = onPlayArtist,
                    )
                }
                if (sectionData.items.isNotEmpty()) {
                    item(key = section.kind.id) {
                        HomeSection(
                            title = section.kind.label,
                            items = sectionData.items,
                            compactCards = compactCards,
                            onClick = sectionData.onClick,
                            onPlay = sectionData.onPlay,
                        )
                    }
                }
            }

        if (
            state.resumeAudiobooks.isEmpty() &&
            state.playlists.isEmpty() &&
            state.recentAlbums.isEmpty() &&
            state.artists.isEmpty()
        ) {
            item {
                Text(
                    "Nothing to show yet.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun HomeSection(
    title: String,
    items: List<LibraryItem>,
    compactCards: Boolean,
    onClick: (LibraryItem) -> Unit,
    onPlay: (LibraryItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = { it.id }) { item ->
                HomeTile(
                    item = item,
                    compact = compactCards,
                    onClick = { onClick(item) },
                    onPlay = { onPlay(item) },
                )
            }
        }
    }
}

@Composable
private fun HomeTile(
    item: LibraryItem,
    compact: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val width = if (compact) 116.dp else 148.dp
    val playSize = if (compact) 34.dp else 40.dp
    val textPadding = if (compact) 10.dp else 12.dp
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.width(width).clickable(onClick = onClick),
    ) {
        Box {
            Artwork(
                item.imageUrl,
                item.title,
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
            )
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(playSize),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play ${item.title}")
            }
        }
        Column(modifier = Modifier.padding(textPadding)) {
            Text(
                item.title,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeCustomizeSheet(
    compactCards: Boolean,
    sections: List<HomeSectionPreference>,
    onCompactCardsChange: (Boolean) -> Unit,
    onSectionVisibleChange: (HomeSectionKind, Boolean) -> Unit,
    onMoveSection: (HomeSectionKind, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        ) {
            Text(
                "Customize Home",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                headlineContent = { Text("Small cards") },
                supportingContent = { Text("Show more items in each row") },
                trailingContent = {
                    Switch(checked = compactCards, onCheckedChange = onCompactCardsChange)
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            sections.forEachIndexed { index, section ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    headlineContent = { Text(section.kind.label) },
                    leadingContent = {
                        Switch(
                            checked = section.visible,
                            onCheckedChange = { onSectionVisibleChange(section.kind, it) },
                        )
                    },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(
                                onClick = { onMoveSection(section.kind, -1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${section.kind.label} up")
                            }
                            IconButton(
                                onClick = { onMoveSection(section.kind, 1) },
                                enabled = index < sections.lastIndex,
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${section.kind.label} down")
                            }
                        }
                    },
                )
            }
        }
    }
}

private data class HomeSectionData(
    val items: List<LibraryItem>,
    val onClick: (LibraryItem) -> Unit,
    val onPlay: (LibraryItem) -> Unit,
)
