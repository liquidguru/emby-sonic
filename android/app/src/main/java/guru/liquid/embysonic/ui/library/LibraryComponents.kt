package guru.liquid.embysonic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import guru.liquid.embysonic.data.emby.LibraryItem
import kotlinx.coroutines.launch

internal val ScreenPadding = 20.dp
internal val GridSpacing = 16.dp

/** Renders a tab's [TabState], centring Loading/Error/empty messages. */
@Composable
internal fun StateContent(state: TabState, content: @Composable (List<LibraryItem>) -> Unit) {
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

/** 2-column card grid of artists/albums/authors/books. Whole card is the tap target. */
@Composable
internal fun CardGrid(
    items: List<LibraryItem>,
    placeholderBook: Boolean,
    onItemClick: (LibraryItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val index = remember(items) { letterIndex(items) }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding + 8.dp,
            top = GridSpacing,
            bottom = GridSpacing,
        ),
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
                modifier = Modifier.clickable { onItemClick(item) },
            ) {
                Artwork(
                    item.imageUrl,
                    item.title,
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    placeholderBook = placeholderBook,
                )
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
        AzIndexBar(index.keys.toList()) { letter ->
            scope.launch { gridState.scrollToItem(index[letter] ?: 0) }
        }
    }
}

/** Top-bar action that toggles between card and list view, showing the target mode's icon. */
@Composable
internal fun ViewToggleAction(listView: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        if (listView) {
            Icon(Icons.Default.GridView, contentDescription = "Card view")
        } else {
            Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "List view")
        }
    }
}

/** Single-column list of artists/albums/authors/books. Each row is the tap target. */
@Composable
internal fun CollectionList(
    items: List<LibraryItem>,
    placeholderBook: Boolean,
    onItemClick: (LibraryItem) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val index = remember(items) { letterIndex(items) }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp, end = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { item ->
                ListItem(
                    modifier = Modifier.clickable { onItemClick(item) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Artwork(
                            item.imageUrl,
                            item.title,
                            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                            placeholderBook = placeholderBook,
                        )
                    },
                    headlineContent = {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = item.subtitle?.let {
                        { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                )
            }
        }
        AzIndexBar(index.keys.toList()) { letter ->
            scope.launch { listState.scrollToItem(index[letter] ?: 0) }
        }
    }
}

/**
 * Right-edge A–Z fast-scroll index. Tap or drag to jump to the first item whose
 * title starts with that letter. Only the letters actually present are shown.
 */
@Composable
private fun BoxScope.AzIndexBar(letters: List<Char>, onSelect: (Char) -> Unit) {
    if (letters.size < 2) return
    fun pick(y: Float, height: Int) {
        if (height <= 0) return
        val idx = ((y / height) * letters.size).toInt().coerceIn(0, letters.size - 1)
        onSelect(letters[idx])
    }
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(24.dp)
            .pointerInput(letters) {
                detectTapGestures { pick(it.y, size.height) }
            }
            .pointerInput(letters) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    pick(change.position.y, size.height)
                }
            },
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { c ->
            Text(
                c.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** First index of each leading letter in a (server-sorted) list; non-letters bucket under '#'. */
internal fun letterIndex(items: List<LibraryItem>): LinkedHashMap<Char, Int> {
    val map = LinkedHashMap<Char, Int>()
    items.forEachIndexed { i, item ->
        val ch = item.title.firstOrNull()?.uppercaseChar()
        val key = if (ch != null && ch.isLetter()) ch else '#'
        if (!map.containsKey(key)) map[key] = i
    }
    return map
}

/** A row-overflow action, e.g. "More like this" / "Start radio". */
internal data class TrackAction(val label: String, val onClick: (LibraryItem) -> Unit)

/**
 * Track/chapter list. Leaf level; row playback wiring comes in M3. [actions], when
 * non-empty, populate each row's overflow menu (music-only sonic playlist actions).
 */
@Composable
internal fun TrackList(
    items: List<LibraryItem>,
    placeholderBook: Boolean,
    actions: List<TrackAction> = emptyList(),
) {
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
                        placeholderBook = placeholderBook,
                    )
                },
                headlineContent = {
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = item.subtitle?.let {
                    { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.trailingText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        TrackOverflow(item, actions)
                    }
                },
            )
        }
    }
}

/** Overflow button + dropdown of [actions] for a single track row. */
@Composable
private fun TrackOverflow(item: LibraryItem, actions: List<TrackAction>) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { if (actions.isNotEmpty()) expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = {
                    expanded = false
                    action.onClick(item)
                },
            )
        }
    }
}

/**
 * Item artwork with a placeholder tile when no image exists. Audiobooks have no
 * cover art in Emby, so they get a book icon instead of the music note.
 */
@Composable
internal fun Artwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier,
    placeholderBook: Boolean = false,
) {
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
                if (placeholderBook) Icons.AutoMirrored.Filled.MenuBook else Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
