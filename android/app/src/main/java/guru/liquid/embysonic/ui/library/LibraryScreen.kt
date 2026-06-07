package guru.liquid.embysonic.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val tabTitles = listOf("Artists", "Albums", "Tracks")

/**
 * Library browser shell. Tab content is wired to the Emby `/Items` + `/Artists`
 * endpoints in M2 once the DTOs land; for now each tab is an honest placeholder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(contentPadding: PaddingValues = PaddingValues()) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        topBar = { TopAppBar(title = { Text("Library") }) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                        )
                    }
                }
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${tabTitles[selectedTab]} — coming in M2",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
