package guru.liquid.embysonic.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.liquid.embysonic.data.emby.DetailKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenItem: (itemId: String, title: String, detailKind: DetailKind) -> Unit = { _, _, _ -> },
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.openNowPlaying.collect { onOpenNowPlaying() }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TrackSearchField(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                placeholder = "Search " + scope.name.lowercase(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewModel.scopes.forEach { s ->
                    FilterChip(
                        selected = scope == s,
                        onClick = { viewModel.setScope(s) },
                        label = { Text(s.label()) },
                    )
                }
            }
            TrackSearchResults(
                state = results,
                placeholderBook = scope == SearchScope.BOOKS || scope == SearchScope.AUTHORS,
                onPick = { item ->
                    when (scope) {
                        SearchScope.TRACKS -> viewModel.playTrack(item)
                        SearchScope.ALBUMS -> onOpenItem(item.id, item.title, DetailKind.ALBUM_TRACKS)
                        SearchScope.ARTISTS -> onOpenItem(item.id, item.title, DetailKind.ARTIST_ALBUMS)
                        SearchScope.BOOKS -> onOpenItem(item.id, item.title, DetailKind.BOOK_CHAPTERS)
                        SearchScope.AUTHORS -> onOpenItem(item.id, item.title, DetailKind.AUTHOR_BOOKS)
                    }
                },
            )
        }
    }
}

private fun SearchScope.label(): String = when (this) {
    SearchScope.TRACKS -> "Tracks"
    SearchScope.ALBUMS -> "Albums"
    SearchScope.ARTISTS -> "Artists"
    SearchScope.BOOKS -> "Books"
    SearchScope.AUTHORS -> "Authors"
}
