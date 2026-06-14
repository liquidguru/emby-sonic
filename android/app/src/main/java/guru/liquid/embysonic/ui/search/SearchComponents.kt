package guru.liquid.embysonic.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.ui.library.Artwork

/** Search input field with a search icon and a clear button. */
@Composable
fun TrackSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search tracks",
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        placeholder = { Text(placeholder) },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Renders search [state]; tapping a result calls [onPick]. */
@Composable
fun TrackSearchResults(
    state: SearchResults,
    onPick: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    placeholderBook: Boolean = false,
) {
    when (state) {
        SearchResults.Empty -> CenterHint(modifier, "Type to search your library")
        SearchResults.Loading -> Box(modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        is SearchResults.Error -> CenterHint(modifier, state.message)
        is SearchResults.Data -> {
            if (state.items.isEmpty()) {
                CenterHint(modifier, "No matches")
            } else {
                LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(state.items, key = { it.id }) { item ->
                        ListItem(
                            modifier = Modifier.clickable { onPick(item) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = {
                                Artwork(
                                    item.imageUrl,
                                    item.title,
                                    Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                    placeholderBook = placeholderBook,
                                )
                            },
                            headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = item.subtitle?.let {
                                { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterHint(modifier: Modifier, text: String) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
