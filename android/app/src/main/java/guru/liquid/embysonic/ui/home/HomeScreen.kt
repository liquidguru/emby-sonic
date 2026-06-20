package guru.liquid.embysonic.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
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
    onOpenMixes: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenAdventure: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var customizeHome by remember { mutableStateOf(false) }
    var deletePlaylistTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val deletePlaylistTarget = state.playlists.firstOrNull { it.id == deletePlaylistTargetId }

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
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
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
                onOpenMixes = onOpenMixes,
                onOpenAdventure = onOpenAdventure,
                onPlayStation = viewModel::playStation,
                onPlayPlaylist = viewModel::playPlaylist,
                onDeletePlaylist = { deletePlaylistTargetId = it.id },
                onPlaySonicMix = viewModel::playSonicMix,
                onPlayAlbum = viewModel::playAlbum,
                onPlayArtist = viewModel::playArtist,
                onPlayResumeAudiobook = viewModel::playResumeAudiobook,
                onPlayRecent = viewModel::playRecent,
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
    if (deletePlaylistTarget != null) {
        AlertDialog(
            onDismissRequest = { deletePlaylistTargetId = null },
            title = { Text("Delete playlist") },
            text = { Text("Delete \"${deletePlaylistTarget.title}\" from Emby? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(deletePlaylistTarget)
                        deletePlaylistTargetId = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePlaylistTargetId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenItem: (itemId: String, title: String, detailKind: DetailKind) -> Unit,
    onOpenMixes: () -> Unit,
    onOpenAdventure: () -> Unit,
    onPlayStation: (HomeStation, Int?) -> Unit,
    onPlayPlaylist: (LibraryItem) -> Unit,
    onDeletePlaylist: (LibraryItem) -> Unit,
    onPlaySonicMix: (LibraryItem) -> Unit,
    onPlayAlbum: (LibraryItem) -> Unit,
    onPlayArtist: (LibraryItem) -> Unit,
    onPlayResumeAudiobook: (LibraryItem) -> Unit,
    onPlayRecent: (LibraryItem) -> Unit,
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

        // Stations: tap-to-play radios. Shown when a music library is present
        // (any music-derived row has content).
        if (state.recentAlbums.isNotEmpty() || state.artists.isNotEmpty()) {
            item(key = "stations") {
                StationsRow(
                    genres = state.genres,
                    onPlayStation = onPlayStation,
                    onOpenGenre = { onOpenItem(it.id, it.title, DetailKind.GENRE_TRACKS) },
                    onOpenAdventure = onOpenAdventure,
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
                    HomeSectionKind.RECENT_PLAYS -> HomeSectionData(
                        // Each tile is a recorded session; tap or play replays its
                        // exact stored queue (works for generated radios too).
                        items = state.recentPlays,
                        onClick = onPlayRecent,
                        onPlay = onPlayRecent,
                    )
                    HomeSectionKind.PLAYLISTS -> HomeSectionData(
                        items = state.playlists,
                        onClick = { onOpenItem(it.id, it.title, DetailKind.PLAYLIST_TRACKS) },
                        onPlay = onPlayPlaylist,
                        onLongPress = onDeletePlaylist,
                    )
                    HomeSectionKind.SONIC_MIXES -> HomeSectionData(
                        items = state.sonicMixes,
                        onClick = { onOpenMixes() },
                        onPlay = onPlaySonicMix,
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
                            onLongPress = sectionData.onLongPress,
                        )
                    }
                }
            }

        if (
            state.resumeAudiobooks.isEmpty() &&
            state.recentPlays.isEmpty() &&
            state.playlists.isEmpty() &&
            state.sonicMixes.isEmpty() &&
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationsRow(
    genres: List<LibraryItem>,
    onPlayStation: (HomeStation, Int?) -> Unit,
    onOpenGenre: (LibraryItem) -> Unit,
    onOpenAdventure: () -> Unit,
) {
    var decadePicker by remember { mutableStateOf(false) }
    var genrePicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Stations",
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        // All stations visible at once: a 3-per-row grid that wraps (3 + 2) so
        // nothing is hidden behind a horizontal scroll.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3,
        ) {
            StationCard(Icons.Default.Shuffle, "Library\nRadio") {
                onPlayStation(HomeStation.LIBRARY, null)
            }
            StationCard(Icons.Default.Album, "Random\nAlbum") {
                onPlayStation(HomeStation.RANDOM_ALBUM, null)
            }
            StationCard(Icons.Default.DateRange, "Decade\nRadio") { decadePicker = true }
            StationCard(Icons.Default.Category, "Genres") { genrePicker = true }
            StationCard(Icons.Default.Explore, "Sonic\nAdventure", onClick = onOpenAdventure)
        }
    }
    if (decadePicker) {
        DecadePickerDialog(
            onDismiss = { decadePicker = false },
            onPick = { decade ->
                decadePicker = false
                onPlayStation(HomeStation.DECADE, decade)
            },
        )
    }
    if (genrePicker) {
        GenrePickerDialog(
            genres = genres,
            onDismiss = { genrePicker = false },
            onPick = { genre ->
                genrePicker = false
                onOpenGenre(genre)
            },
        )
    }
}

@Composable
private fun StationCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.size(108.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun DecadePickerDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a decade") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(1960, 1970, 1980, 1990, 2000, 2010, 2020).chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { decade ->
                            DecadeCard(Modifier.weight(1f), decade) { onPick(decade) }
                        }
                        // Pad a short final row so the tiles keep square sizing.
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GenrePickerDialog(
    genres: List<LibraryItem>,
    onDismiss: () -> Unit,
    onPick: (LibraryItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a genre") },
        text = {
            if (genres.isEmpty()) {
                Text("No genres found yet.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                ) {
                    items(genres, key = { it.id }) { genre ->
                        ListItem(
                            modifier = Modifier.clickable { onPick(genre) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            headlineContent = {
                                Text(
                                    genre.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DecadeCard(modifier: Modifier, decadeStart: Int, onClick: () -> Unit) {
    Card(
        modifier = modifier.aspectRatio(1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "${decadeStart}s",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
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
    onLongPress: ((LibraryItem) -> Unit)? = null,
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
                    onLongPress = onLongPress?.let { { it(item) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeTile(
    item: LibraryItem,
    compact: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val width = if (compact) 116.dp else 148.dp
    val playSize = if (compact) 34.dp else 40.dp
    val textPadding = if (compact) 10.dp else 12.dp
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .width(width)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
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
    val onLongPress: ((LibraryItem) -> Unit)? = null,
)
