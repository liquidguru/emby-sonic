package guru.liquid.embysonic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.liquid.embysonic.playback.EqBand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    onBack: () -> Unit,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.available) {
                        TextButton(onClick = viewModel::reset, enabled = state.enabled) {
                            Text("Flat")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!state.available) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Equalizer isn't available on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Equalizer", style = MaterialTheme.typography.titleLarge)
                Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
            }

            if (state.presets.isNotEmpty()) {
                Text("Presets", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.presets.forEachIndexed { index, name ->
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.usePreset(index) },
                            label = { Text(name) },
                            enabled = state.enabled,
                        )
                    }
                }
            }

            Text("Bands", style = MaterialTheme.typography.titleMedium)
            state.bands.forEach { band ->
                BandRow(
                    band = band,
                    minMb = state.minLevelMb,
                    maxMb = state.maxLevelMb,
                    enabled = state.enabled,
                    onLevelChange = { viewModel.setBandLevel(band.index, it) },
                    onLevelChangeFinished = viewModel::persistBandLevels,
                )
            }
        }
    }
}

@Composable
private fun BandRow(
    band: EqBand,
    minMb: Int,
    maxMb: Int,
    enabled: Boolean,
    onLevelChange: (Int) -> Unit,
    onLevelChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatFreq(band.centerFreqHz),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp),
        )
        Slider(
            value = band.levelMb.toFloat(),
            onValueChange = { onLevelChange(it.toInt()) },
            onValueChangeFinished = onLevelChangeFinished,
            valueRange = minMb.toFloat()..maxMb.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            "%+d dB".format(band.levelMb / 100),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

private fun formatFreq(hz: Int): String =
    if (hz >= 1000) {
        val k = hz / 1000f
        if (k % 1f == 0f) "${k.toInt()} kHz" else "%.1f kHz".format(k)
    } else {
        "$hz Hz"
    }
