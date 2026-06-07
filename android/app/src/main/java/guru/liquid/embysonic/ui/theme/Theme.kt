package guru.liquid.embysonic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4FC3F7)
private val AccentDark = Color(0xFF0288D1)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00131F),
    secondary = Color(0xFF81D4FA),
    background = Color(0xFF0B1020),
    surface = Color(0xFF131A2E),
    onBackground = Color(0xFFE3E7F0),
    onSurface = Color(0xFFE3E7F0),
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    secondary = Accent,
)

@Composable
fun EmbySonicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
