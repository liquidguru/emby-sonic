package guru.liquid.embysonic.widget

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.content.ContextCompat
import guru.liquid.embysonic.data.settings.ThemeChoice

/**
 * Flat colour set the Now Playing widget paints onto its RemoteViews so it
 * matches the in-app theme. All colours are opaque; the widget's background
 * drawables supply their own translucency, and the tint only recolours them.
 */
data class WidgetPalette(
    val surface: Int,
    val artBackground: Int,
    val accent: Int,
    val textPrimary: Int,
    val textSecondary: Int,
)

object WidgetTheme {

    fun paletteFor(context: Context, choice: ThemeChoice): WidgetPalette =
        when (choice) {
            ThemeChoice.DYNAMIC -> dynamicPalette(context)
            // Mirror the in-app palettes in ui/theme/Theme.kt (surface, background,
            // primary, onBackground, onSurfaceVariant).
            ThemeChoice.LIQUID_WAVE -> palette("131A2E", "0B1020", "4FC3F7", "E3E7F0", "9AA3B8")
            ThemeChoice.EMBER -> palette("211A15", "14100E", "FFB74D", "F0E7DF", "BCA48E")
            ThemeChoice.VIOLET -> palette("1E1830", "14101F", "B388FF", "E8E2F2", "A296BF")
            ThemeChoice.FOREST -> palette("15211A", "0D140F", "66BB6A", "E2EDE4", "8FAE96")
            ThemeChoice.ROSE -> palette("241620", "170E12", "FF6E8E", "F2E2E8", "BD93A4")
        }

    private fun dynamicPalette(context: Context): WidgetPalette {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return paletteFor(context, ThemeChoice.LIQUID_WAVE)
        }
        fun color(resId: Int) = ContextCompat.getColor(context, resId)
        return WidgetPalette(
            surface = color(android.R.color.system_neutral1_800),
            artBackground = color(android.R.color.system_neutral1_900),
            accent = color(android.R.color.system_accent1_200),
            textPrimary = color(android.R.color.system_neutral1_50),
            textSecondary = color(android.R.color.system_neutral2_200),
        )
    }

    private fun palette(
        surface: String,
        background: String,
        accent: String,
        textPrimary: String,
        textSecondary: String,
    ) = WidgetPalette(
        surface = Color.parseColor("#$surface"),
        artBackground = Color.parseColor("#$background"),
        accent = Color.parseColor("#$accent"),
        textPrimary = Color.parseColor("#$textPrimary"),
        textSecondary = Color.parseColor("#$textSecondary"),
    )
}
