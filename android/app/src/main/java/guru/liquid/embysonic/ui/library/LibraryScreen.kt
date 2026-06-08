package guru.liquid.embysonic.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.liquid.embysonic.R
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    contentPadding: PaddingValues = PaddingValues(),
    onOpenItem: (itemId: String, title: String, detailKind: DetailKind) -> Unit = { _, _, _ -> },
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listView by viewModel.listView.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val tabTitles = viewModel.tabTitles
    val isMusic = viewModel.kind == LibraryKind.MUSIC
    val placeholderBook = !isMusic

    val snackbarHostState = remember { SnackbarHostState() }
    var showNameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    // Selection is per-tab (albums vs artists differ); leaving a tab clears it.
    LaunchedEffect(selectedTab) { viewModel.exitSelection() }

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::exitSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = { Text("${selectedIds.size} selected") },
                    actions = {
                        IconButton(
                            onClick = { showNameDialog = true },
                            enabled = selectedIds.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Create playlist")
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                    title = { Text(viewModel.title) },
                    actions = {
                        ViewToggleAction(listView = listView, onToggle = viewModel::toggleListView)
                        if (isMusic) {
                            IconButton(onClick = viewModel::enterSelection) {
                                Icon(
                                    Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "Make playlist",
                                )
                            }
                        }
                        // TODO(M2+): wire library search + sort/filter overflow.
                        IconButton(onClick = {}) { Icon(Icons.Default.Search, contentDescription = "Search") }
                        IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }
            // Tab 0 = Artists/Authors, Tab 1 = Albums/Books. Tapping a cell drills down,
            // or toggles selection while building a playlist.
            val detailKind = viewModel.kind.detailKindFor(selectedTab)
            val tabState = if (selectedTab == 0) state.artists else state.albums
            StateContent(tabState) { items ->
                val onClick = { item: LibraryItem ->
                    if (selectionMode) viewModel.toggleSelected(item.id)
                    else onOpenItem(item.id, item.title, detailKind)
                }
                val selection = if (selectionMode) selectedIds else emptySet()
                if (listView) {
                    CollectionList(items, placeholderBook, onClick, selection)
                } else {
                    CardGrid(items, placeholderBook, onClick, selection)
                }
            }
        }
    }

    if (showNameDialog) {
        val defaultName = "New playlist"
        var name by remember { mutableStateOf(defaultName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Save playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNameDialog = false
                    viewModel.createPlaylistFromSelection(
                        name.ifBlank { defaultName },
                        areAlbums = selectedTab == 1,
                    )
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** Which drill-down a tapped cell opens, given the library kind and tab index. */
private fun LibraryKind.detailKindFor(tab: Int): DetailKind = when (this) {
    LibraryKind.MUSIC -> if (tab == 0) DetailKind.ARTIST_ALBUMS else DetailKind.ALBUM_TRACKS
    LibraryKind.AUDIOBOOKS -> if (tab == 0) DetailKind.AUTHOR_BOOKS else DetailKind.BOOK_CHAPTERS
}
