package guru.liquid.embysonic.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import guru.liquid.embysonic.data.settings.ThemeChoice

// Emby Sonic is dark-first (Plexamp-style). Every theme is a dark palette; a
// light theme is intentionally omitted until one is actually designed. DYNAMIC
// pulls Material You colours from the system on Android 12+ and falls back to
// the liquidWave palette below that.

private val LiquidWaveColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00131F),
    secondary = Color(0xFF81D4FA),
    background = Color(0xFF0B1020),
    surface = Color(0xFF131A2E),
    surfaceVariant = Color(0xFF1B2440),
    onBackground = Color(0xFFE3E7F0),
    onSurface = Color(0xFFE3E7F0),
    onSurfaceVariant = Color(0xFF9AA4BF),
)

private val EmberColors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF3A2402),
    secondary = Color(0xFFFF8A65),
    background = Color(0xFF14100E),
    surface = Color(0xFF211A15),
    surfaceVariant = Color(0xFF2C2117),
    onBackground = Color(0xFFF0E7DF),
    onSurface = Color(0xFFF0E7DF),
    onSurfaceVariant = Color(0xFFBCA48E),
)

private val VioletColors = darkColorScheme(
    primary = Color(0xFFB388FF),
    onPrimary = Color(0xFF241640),
    secondary = Color(0xFF9FA8FF),
    background = Color(0xFF14101F),
    surface = Color(0xFF1E1830),
    surfaceVariant = Color(0xFF2A2142),
    onBackground = Color(0xFFE8E2F2),
    onSurface = Color(0xFFE8E2F2),
    onSurfaceVariant = Color(0xFFA296BF),
)

private val ForestColors = darkColorScheme(
    primary = Color(0xFF66BB6A),
    onPrimary = Color(0xFF0C2A10),
    secondary = Color(0xFF9CCC65),
    background = Color(0xFF0D140F),
    surface = Color(0xFF15211A),
    surfaceVariant = Color(0xFF1D2C22),
    onBackground = Color(0xFFE2EDE4),
    onSurface = Color(0xFFE2EDE4),
    onSurfaceVariant = Color(0xFF8FAE96),
)

private val RoseColors = darkColorScheme(
    primary = Color(0xFFFF6E8E),
    onPrimary = Color(0xFF3A0E1C),
    secondary = Color(0xFFFFA0B4),
    background = Color(0xFF170E12),
    surface = Color(0xFF241620),
    surfaceVariant = Color(0xFF30202B),
    onBackground = Color(0xFFF2E2E8),
    onSurface = Color(0xFFF2E2E8),
    onSurfaceVariant = Color(0xFFBD93A4),
)

@Composable
fun EmbySonicTheme(
    themeChoice: ThemeChoice = ThemeChoice.DEFAULT,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when (themeChoice) {
        ThemeChoice.DYNAMIC ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                LiquidWaveColors
            }
        ThemeChoice.LIQUID_WAVE -> LiquidWaveColors
        ThemeChoice.EMBER -> EmberColors
        ThemeChoice.VIOLET -> VioletColors
        ThemeChoice.FOREST -> ForestColors
        ThemeChoice.ROSE -> RoseColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
