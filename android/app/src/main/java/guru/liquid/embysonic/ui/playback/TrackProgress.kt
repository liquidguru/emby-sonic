package guru.liquid.embysonic.ui.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import guru.liquid.embysonic.playback.PlaybackUiState

interface TrackProgress {
    @Composable
    fun Render(
        state: PlaybackUiState,
        onSeek: (Long) -> Unit,
        modifier: Modifier,
    )
}

object SliderTrackProgress : TrackProgress {
    @Composable
    override fun Render(
        state: PlaybackUiState,
        onSeek: (Long) -> Unit,
        modifier: Modifier,
    ) {
        val duration = state.durationMs.coerceAtLeast(0)
        val fraction = remember(state.positionMs, duration) {
            if (duration <= 0) 0f else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
        }

        Card(
            modifier = modifier.height(96.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .pointerInput(duration) {
                            detectTapGestures { offset ->
                                if (duration > 0) {
                                    val next = (offset.x / size.width).coerceIn(0f, 1f)
                                    onSeek((duration * next).toLong())
                                }
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(12.dp)
                            .widthIn(min = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatTime(state.positionMs), style = MaterialTheme.typography.bodyMedium)
                    Text("-${formatTime((duration - state.positionMs).coerceAtLeast(0))}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
