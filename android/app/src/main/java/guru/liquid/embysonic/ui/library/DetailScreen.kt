package guru.liquid.embysonic.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listView by viewModel.listView.collectAsStateWithLifecycle()
    val kind = viewModel.kind

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
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
                    if (listView) {
                        CollectionList(items, placeholderBook = kind.usesBookIcon, onItemClick = onClick)
                    } else {
                        CardGrid(items, placeholderBook = kind.usesBookIcon, onItemClick = onClick)
                    }
                } else {
                    TrackList(items, placeholderBook = kind.usesBookIcon)
                }
            }
        }
    }
}
