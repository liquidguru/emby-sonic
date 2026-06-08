package guru.liquid.embysonic.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    val tabTitles = viewModel.tabTitles
    val placeholderBook = viewModel.kind == LibraryKind.AUDIOBOOKS

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        topBar = {
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
                    // TODO(M2+): wire library search + sort/filter overflow.
                    IconButton(onClick = {}) { Icon(Icons.Default.Search, contentDescription = "Search") }
                    IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                },
            )
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
            // Tab 0 = Artists/Authors, Tab 1 = Albums/Books. Tapping a cell drills down.
            val detailKind = viewModel.kind.detailKindFor(selectedTab)
            val tabState = if (selectedTab == 0) state.artists else state.albums
            StateContent(tabState) { items ->
                CardGrid(items, placeholderBook = placeholderBook) { item ->
                    onOpenItem(item.id, item.title, detailKind)
                }
            }
        }
    }
}

/** Which drill-down a tapped cell opens, given the library kind and tab index. */
private fun LibraryKind.detailKindFor(tab: Int): DetailKind = when (this) {
    LibraryKind.MUSIC -> if (tab == 0) DetailKind.ARTIST_ALBUMS else DetailKind.ALBUM_TRACKS
    LibraryKind.AUDIOBOOKS -> if (tab == 0) DetailKind.AUTHOR_BOOKS else DetailKind.BOOK_CHAPTERS
}
