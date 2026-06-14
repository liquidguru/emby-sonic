package guru.liquid.embysonic.ui.adventure

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.ui.library.Artwork
import guru.liquid.embysonic.ui.library.TrackList
import guru.liquid.embysonic.ui.search.SearchViewModel
import guru.liquid.embysonic.ui.search.TrackSearchField
import guru.liquid.embysonic.ui.search.TrackSearchResults

private enum class PickTarget { START, END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdventureScreen(
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: AdventureViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pickerTarget by remember { mutableStateOf<PickTarget?>(null) }
    var saveDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.openNowPlaying.collect { onOpenNowPlaying() }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Sonic Adventure") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "A journey that morphs from one track to another.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            EndpointCard("Start", state.start) { pickerTarget = PickTarget.START }
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            EndpointCard("End", state.end) { pickerTarget = PickTarget.END }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Length", style = MaterialTheme.typography.bodyMedium)
                listOf(10, 15, 20, 25).forEach { n ->
                    FilterChip(
                        selected = state.length == n,
                        onClick = { viewModel.setLength(n) },
                        label = { Text("$n") },
                    )
                }
            }

            Button(
                onClick = viewModel::generate,
                enabled = state.start != null && state.end != null &&
                    state.result !is AdventureResult.Loading,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Text("Generate adventure")
            }

            AdventureResultBody(
                result = state.result,
                onPlayAll = viewModel::play,
                onPlayTrack = viewModel::playFrom,
                onSave = { saveDialog = true },
            )
        }
    }

    if (pickerTarget != null) {
        val target = pickerTarget!!
        val query by searchViewModel.query.collectAsStateWithLifecycle()
        val results by searchViewModel.results.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = { pickerTarget = null }) {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding(),
            ) {
                Text(
                    if (target == PickTarget.START) "Pick a start track" else "Pick a destination track",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                TrackSearchField(
                    query = query,
                    onQueryChange = searchViewModel::onQueryChange,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    TrackSearchResults(
                        state = results,
                        onPick = { item ->
                            if (target == PickTarget.START) viewModel.setStart(item) else viewModel.setEnd(item)
                            pickerTarget = null
                        },
                    )
                }
            }
        }
    }

    if (saveDialog) {
        var name by rememberSaveable { mutableStateOf("Sonic Adventure") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
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
                TextButton(onClick = { saveDialog = false; viewModel.saveAsPlaylist(name) }, enabled = name.isNotBlank()) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EndpointCard(label: String, item: LibraryItem?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item != null) {
                Artwork(item.imageUrl, item.title, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
            } else {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    item?.title ?: "Pick a track",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item?.subtitle?.let {
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
}

@Composable
private fun AdventureResultBody(
    result: AdventureResult,
    onPlayAll: () -> Unit,
    onPlayTrack: (LibraryItem) -> Unit,
    onSave: () -> Unit,
) {
    when (result) {
        AdventureResult.Idle -> Unit
        AdventureResult.Loading -> Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        is AdventureResult.Error -> Text(
            result.message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(20.dp),
        )
        is AdventureResult.Data -> Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onPlayAll) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Play", modifier = Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = onSave) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                    Text("Save", modifier = Modifier.padding(start = 6.dp))
                }
            }
            TrackList(
                items = result.tracks,
                placeholderBook = false,
                onTrackClick = onPlayTrack,
            )
        }
    }
}
