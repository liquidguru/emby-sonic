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
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            StateContent(state) { items ->
                if (kind.isGrid) {
                    CardGrid(items, placeholderBook = kind.usesBookIcon) { item ->
                        kind.childKind?.let { child -> onOpenItem(item.id, item.title, child) }
                    }
                } else {
                    TrackList(items, placeholderBook = kind.usesBookIcon)
                }
            }
        }
    }
}
