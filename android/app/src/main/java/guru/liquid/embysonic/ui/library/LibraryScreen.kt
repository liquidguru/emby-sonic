package guru.liquid.embysonic.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import guru.liquid.embysonic.R
import guru.liquid.embysonic.data.emby.LibraryItem

private val ScreenPadding = 20.dp
private val GridSpacing = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabTitles = viewModel.tabTitles

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
            when (selectedTab) {
                0 -> TabContent(state.artists) { CardGrid(it) }
                1 -> TabContent(state.albums) { CardGrid(it) }
                else -> TabContent(state.tracks) { TrackList(it) }
            }
        }
    }
}

@Composable
private fun TabContent(state: TabState, content: @Composable (List<LibraryItem>) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            is TabState.Loading -> CircularProgressIndicator()
            is TabState.Error -> Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            is TabState.Data ->
                if (state.items.isEmpty()) Text("Nothing here yet") else content(state.items)
        }
    }
}

@Composable
private fun CardGrid(items: List<LibraryItem>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = GridSpacing),
        horizontalArrangement = Arrangement.spacedBy(GridSpacing),
        verticalArrangement = Arrangement.spacedBy(GridSpacing),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Artwork(item.imageUrl, item.title, Modifier.fillMaxWidth().aspectRatio(1f))
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Album/artist artwork with a music-note placeholder tile when no image exists. */
@Composable
private fun Artwork(url: String?, contentDescription: String?, modifier: Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun TrackList(items: List<LibraryItem>) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Artwork(
                        item.imageUrl,
                        item.title,
                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                    )
                },
                headlineContent = {
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = item.subtitle?.let {
                    { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                trailingContent = {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        item.trailingText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                    }
                },
            )
        }
    }
}
