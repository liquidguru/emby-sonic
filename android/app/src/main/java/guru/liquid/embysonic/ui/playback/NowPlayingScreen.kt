package guru.liquid.embysonic.ui.playback

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.playback.PlaybackRepeatMode
import guru.liquid.embysonic.playback.PlaybackTrack
import guru.liquid.embysonic.playback.PlaybackUiState
import guru.liquid.embysonic.ui.library.Artwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onCollapse: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val radio by viewModel.radio.collectAsStateWithLifecycle()
    val progress = remember { SliderTrackProgress }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var queueFocusRequest by remember { mutableIntStateOf(0) }

    // Generate a sonic radio when the Radio tab is opened (and when the seed
    // track changes while it's open).
    LaunchedEffect(selectedTab, state.currentTrack?.id) {
        if (selectedTab == 1) viewModel.loadRadioForCurrent()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse")
                    }
                },
                title = {
                    Text(
                        "Now Playing",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            selectedTab = 0
                            queueFocusRequest += 1
                        },
                        enabled = state.queue.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
                    }
                    IconButton(onClick = viewModel::stopPlayback, enabled = state.currentTrack != null) {
                        Icon(Icons.Default.Close, contentDescription = "Stop playback")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF06111F), MaterialTheme.colorScheme.background),
                    ),
                )
                .padding(padding),
        ) {
            state.currentTrack?.let { track ->
                PlayerContent(
                    state = state,
                    track = track,
                    progress = progress,
                    onSeek = viewModel::seekTo,
                    onToggle = viewModel::togglePlayPause,
                    onPrevious = viewModel::skipPrevious,
                    onNext = viewModel::skipNext,
                    onShuffleQueue = viewModel::shuffleQueue,
                    onCycleRepeat = viewModel::cycleRepeatMode,
                    onQueueItemClick = viewModel::seekToQueueIndex,
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    queueFocusRequest = queueFocusRequest,
                    radio = radio,
                    onPlayRadioAll = viewModel::playRadioAll,
                    onPlayRadioTrack = viewModel::playRadioTrack,
                    onRefreshRadio = { viewModel.loadRadioForCurrent(force = true) },
                )
            } ?: EmptyPlayer(onCollapse)
        }
    }
}

@Composable
private fun PlayerContent(
    state: PlaybackUiState,
    track: PlaybackTrack,
    progress: TrackProgress,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffleQueue: () -> Unit,
    onCycleRepeat: () -> Unit,
    onQueueItemClick: (Int) -> Unit,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    queueFocusRequest: Int,
    radio: RadioState,
    onPlayRadioAll: () -> Unit,
    onPlayRadioTrack: (LibraryItem) -> Unit,
    onRefreshRadio: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(queueFocusRequest) {
        if (queueFocusRequest > 0) {
            // Toggle: if the queue is already showing, scroll back up to the
            // player hero; otherwise scroll down to the queue. The same top-bar
            // button takes you to the track list and back, so you're never
            // stranded in the queue with only Stop/Collapse to escape.
            if (listState.firstVisibleItemIndex >= 1) {
                listState.animateScrollToItem(0)
            } else {
                listState.animateScrollToItem(1)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            NowPlayingArtwork(track)
            Spacer(Modifier.height(20.dp))
            Text(
                track.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.artist?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            track.album?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            progress.Render(state, onSeek, Modifier.fillMaxWidth())
            state.playbackError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            TransportControls(state, onPrevious, onToggle, onNext)
            Spacer(Modifier.height(8.dp))
            PlaybackModeControls(
                state = state,
                onShuffleQueue = onShuffleQueue,
                onCycleRepeat = onCycleRepeat,
            )
            Spacer(Modifier.height(18.dp))
            GuestDjRow()
            Spacer(Modifier.height(20.dp))
            PlaybackTabs(selected = selectedTab, onSelect = onSelectTab)
            Spacer(Modifier.height(8.dp))
        }
        when (selectedTab) {
            0 -> itemsIndexed(state.queue, key = { _, item -> item.id }) { index, item ->
                QueueRow(
                    item = item,
                    index = index,
                    selected = index == state.currentIndex,
                    onClick = {
                        if (index != state.currentIndex) {
                            onQueueItemClick(index)
                        }
                    },
                )
            }
            1 -> radioContent(radio, onPlayRadioAll, onPlayRadioTrack, onRefreshRadio)
            else -> item {
                Text(
                    "Similar tracks arrive soon",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

/** Track Radio tab body, rendered as items in the Now Playing LazyColumn. */
private fun LazyListScope.radioContent(
    radio: RadioState,
    onPlayAll: () -> Unit,
    onPlayTrack: (LibraryItem) -> Unit,
    onRefresh: () -> Unit,
) {
    when (radio) {
        RadioState.Idle, RadioState.Loading -> item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is RadioState.Error -> item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(radio.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefresh, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Try again")
                }
            }
        }
        is RadioState.Data -> {
            if (radio.tracks.isEmpty()) {
                item {
                    Text(
                        "No radio for this track yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onPlayAll) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("Play radio", modifier = Modifier.padding(start = 6.dp))
                        }
                        TextButton(onClick = onRefresh) { Text("New radio") }
                    }
                }
                items(radio.tracks, key = { it.id }) { item ->
                    RadioRow(item = item, onClick = { onPlayTrack(item) })
                }
            }
        }
    }
}

@Composable
private fun RadioRow(item: LibraryItem, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = item.subtitle?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            Artwork(
                item.imageUrl,
                item.title,
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            )
        },
        trailingContent = {
            IconButton(onClick = onClick) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play ${item.title}")
            }
        },
    )
}

@Composable
private fun NowPlayingArtwork(track: PlaybackTrack) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.74f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (track.imageUrl != null) {
            AsyncImage(
                model = track.imageUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(96.dp),
            )
        }
    }
}

@Composable
private fun TransportControls(
    state: PlaybackUiState,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = state.hasPrevious || state.positionMs > 3000) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(42.dp))
        }
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        ) {
            if (state.isBuffering) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(38.dp),
                )
            } else {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        IconButton(onClick = onNext, enabled = state.hasNext) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(42.dp))
        }
    }
}

@Composable
private fun PlaybackModeControls(
    state: PlaybackUiState,
    onShuffleQueue: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onShuffleQueue, enabled = state.queue.size > 1) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = "Shuffle queue",
                tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCycleRepeat, enabled = state.queue.isNotEmpty()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (state.repeatMode == PlaybackRepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = when (state.repeatMode) {
                        PlaybackRepeatMode.OFF -> "Repeat off"
                        PlaybackRepeatMode.ALL -> "Repeat all"
                        PlaybackRepeatMode.ONE -> "Repeat one"
                    },
                    tint = if (state.repeatMode == PlaybackRepeatMode.OFF) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                if (state.repeatMode == PlaybackRepeatMode.ALL) {
                    Badge(modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }
}

@Composable
private fun GuestDjRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("Guest DJ", style = MaterialTheme.typography.titleMedium)
            Text("Similar tracks", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = false, onCheckedChange = null, enabled = false)
    }
}

@Composable
private fun PlaybackTabs(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("Queue", "Radio", "Similar")
    val icons = listOf(Icons.AutoMirrored.Filled.QueueMusic, Icons.Default.Waves, Icons.Default.GraphicEq)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        tabs.forEachIndexed { index, label ->
            IconButton(onClick = { onSelect(index) }, modifier = Modifier.size(84.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        icons[index],
                        contentDescription = label,
                        tint = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        label,
                        color = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: PlaybackTrack, index: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        ),
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = item.artist?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            if (selected) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    (index + 1).toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = if (!selected) {
            {
                IconButton(onClick = onClick) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play ${item.title}")
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun EmptyPlayer(onCollapse: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing playing", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Choose a track from an album or playlist.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        IconButton(onClick = onCollapse, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Back")
        }
    }
}
