package guru.liquid.embysonic.ui.mixes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Auto-curated mixes. Wired to `GET /sonic/mixes` in M4. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixesScreen(contentPadding: PaddingValues = PaddingValues()) {
    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        topBar = { TopAppBar(title = { Text("Mixes") }) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Sonic mixes — coming in M4", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
