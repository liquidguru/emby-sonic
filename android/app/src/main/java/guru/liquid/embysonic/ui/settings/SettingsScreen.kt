package guru.liquid.embysonic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.draw.alpha
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
            if (state.isCasting) {
                CastUnavailableHint(deviceName = state.castDeviceName)
            }

            Card(modifier = Modifier.fillMaxWidth().alpha(if (state.isCasting) 0.62f else 1f)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Equalizer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.isCasting) {
                            "Unavailable while casting. The receiver handles its own output."
                        } else {
                            "Tune the local playback EQ and presets."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onOpenEqualizer,
                        enabled = !state.isCasting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open equalizer")
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
                                if (state.isCasting) {
                                    "Unavailable while casting. The receiver streams each track directly."
                                } else {
                                    "Blend one song into the next. Music only - never audiobooks."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.crossfadeEnabled,
                            onCheckedChange = viewModel::setCrossfadeEnabled,
                            enabled = !state.isCasting,
                        )
                    }
                    if (state.crossfadeEnabled) {
                        Text("Overlap length", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(3, 6, 9, 12).forEach { seconds ->
                                FilterChip(
                                    selected = state.crossfadeSeconds == seconds,
                                    onClick = { viewModel.setCrossfadeSeconds(seconds) },
                                    label = { Text("${seconds}s") },
                                    enabled = !state.isCasting,
                                )
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth().alpha(if (state.isCasting) 0.62f else 1f)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Offline prefetch", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.isCasting) {
                            "Unavailable while casting. Cast receivers fetch directly from Emby."
                        } else {
                            "Keeps upcoming tracks buffered on this phone so local playback can ride through short Wi-Fi drops. Cast playback does its own streaming on the receiver."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.coordinatorUrl,
                        onValueChange = viewModel::onCoordinatorUrlChange,
                        label = { Text("Coordinator URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = viewModel::saveCoordinatorUrl, modifier = Modifier.fillMaxWidth()) {
                        Text("Save coordinator URL")
                    }
                    OutlinedTextField(
                        value = state.castServerUrl,
                        onValueChange = viewModel::onCastServerUrlChange,
                        label = { Text("Cast server URL (LAN)") },
                        placeholder = { Text("http://192.168.1.9:8096") },
                        supportingText = {
                            Text("Direct local Emby URL for casting. Leave blank to use the main server URL.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = viewModel::saveCastServerUrl, modifier = Modifier.fillMaxWidth()) {
                        Text("Save cast server URL")
                    }
                    state.savedMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("About & diagnostics", style = MaterialTheme.typography.titleMedium)
                    DiagnosticRow(label = "App version", value = state.appVersion)
                    DiagnosticRow(label = "Emby server", value = state.serverUrl)
                    DiagnosticRow(label = "Coordinator", value = state.coordinatorUrl)
                    DiagnosticRow(
                        label = "Cast server",
                        value = state.castServerUrl.ifBlank { "Using Emby server" },
                    )
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
                            Text("Analysis status", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Coordinator library scan and embedding progress.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                                "${st.pending} pending - ${if (st.scanRunning) "scan running" else "scan idle"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (st.pending > 0 && !st.scanRunning) {
                                Text(
                                    "If this number stays stable after a scan, those tracks are usually unsupported, unreadable, or waiting for a future rescan.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value.ifBlank { "Not set" }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CastUnavailableHint(deviceName: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CastConnected,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    deviceName?.let { "Casting to $it" } ?: "Casting",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Local-only audio features are unavailable while casting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
