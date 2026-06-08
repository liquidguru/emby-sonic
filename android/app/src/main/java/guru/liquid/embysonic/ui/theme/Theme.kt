package guru.liquid.embysonic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4FC3F7)

// Emby Sonic is dark-first (Plexamp-style). The light palette is intentionally
// omitted until a light theme is actually designed; the app forces dark.
private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00131F),
    secondary = Color(0xFF81D4FA),
    background = Color(0xFF0B1020),
    surface = Color(0xFF131A2E),
    surfaceVariant = Color(0xFF1B2440),
    onBackground = Color(0xFFE3E7F0),
    onSurface = Color(0xFFE3E7F0),
    onSurfaceVariant = Color(0xFF9AA4BF),
)

@Composable
fun EmbySonicTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
