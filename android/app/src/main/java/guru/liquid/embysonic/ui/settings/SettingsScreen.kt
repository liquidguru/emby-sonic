package guru.liquid.embysonic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.liquid.embysonic.data.settings.ThemeChoice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.loggedOut) {
        onLoggedOut()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Signed in as ${state.userName}", style = MaterialTheme.typography.titleMedium)
            Text("Emby server: ${state.serverUrl}", style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = state.coordinatorUrl,
                onValueChange = viewModel::onCoordinatorUrlChange,
                label = { Text("Coordinator URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            state.savedMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Button(onClick = viewModel::saveCoordinatorUrl, modifier = Modifier.fillMaxWidth()) {
                Text("Save coordinator URL")
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "App colour theme. Dynamic follows your wallpaper on Android 12+.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeChoice.entries.forEach { choice ->
                            FilterChip(
                                selected = state.themeChoice == choice,
                                onClick = { viewModel.setThemeChoice(choice) },
                                label = { Text(choice.label) },
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Analysis status", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = viewModel::refreshAnalysisStatus) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh analysis status")
                        }
                    }

                    when (val status = state.analysisStatus) {
                        AnalysisStatusUiState.Loading -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }

                        is AnalysisStatusUiState.Error -> {
                            Text(
                                "Coordinator unreachable",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(status.message, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Trying: ${state.coordinatorUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is AnalysisStatusUiState.Ready -> {
                            val st = status.status
                            LinearProgressIndicator(
                                progress = { st.progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${st.analysed} / ${st.total} tracks analysed",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${st.pending} pending - ${if (st.scanRunning) "scan running" else "idle"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Crossfade", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Blend one song into the next. Music only — never audiobooks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.crossfadeEnabled,
                            onCheckedChange = viewModel::setCrossfadeEnabled,
                        )
                    }
                    if (state.crossfadeEnabled) {
                        Text(
                            "Overlap length",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(3, 6, 9, 12).forEach { seconds ->
                                FilterChip(
                                    selected = state.crossfadeSeconds == seconds,
                                    onClick = { viewModel.setCrossfadeSeconds(seconds) },
                                    label = { Text("${seconds}s") },
                                )
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Generated mixes", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Default track count for Sonic mixes and genre mixes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(25, 50, 75, 100).forEach { count ->
                            FilterChip(
                                selected = state.generatedMixTracks == count,
                                onClick = { viewModel.setGeneratedMixTracks(count) },
                                label = { Text(count.toString()) },
                            )
                        }
                    }
                }
            }

            OutlinedButton(onClick = onOpenEqualizer, modifier = Modifier.fillMaxWidth()) {
                Text("Equalizer")
            }

            OutlinedButton(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
        }
    }
}
