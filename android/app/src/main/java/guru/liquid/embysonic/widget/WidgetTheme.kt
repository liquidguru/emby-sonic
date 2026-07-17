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

/**
 * A theme's widget colours for both system appearances.
 *
 * The widget can't read the app's Compose theme (it renders in the launcher's
 * process), and it can't resolve a theme-aware colour attribute either — the
 * Material You resources it uses (`system_neutral1_800` etc.) are FIXED tones, not
 * tokens that flip with light/dark. So both variants are resolved up front and
 * handed to RemoteViews' day/night overloads, which let the launcher pick — and
 * re-pick on a configuration change without us repainting. That matters here:
 * `updatePeriodMillis="0"` and repaints are only pushed on playback/theme changes,
 * so a widget that baked in one appearance would sit stale until the track changed.
 *
 * [day] == [night] for the fixed palettes, which are all dark by design.
 */
data class WidgetPalettes(val day: WidgetPalette, val night: WidgetPalette) {
    /** Pre-31 hosts have no day/night overloads; everything is dark there anyway. */
    val fallback: WidgetPalette get() = night
}

object WidgetTheme {

    fun palettesFor(context: Context, choice: ThemeChoice): WidgetPalettes =
        when (choice) {
            // The only theme that follows the system, mirroring EmbySonicTheme.
            ThemeChoice.DYNAMIC -> dynamicPalettes(context)
            // Mirror the in-app palettes in ui/theme/Theme.kt (surface, background,
            // primary, onBackground, onSurfaceVariant). Dark-only, so day == night.
            ThemeChoice.LIQUID_WAVE -> fixed("131A2E", "0B1020", "4FC3F7", "E3E7F0", "9AA3B8")
            ThemeChoice.EMBER -> fixed("211A15", "14100E", "FFB74D", "F0E7DF", "BCA48E")
            ThemeChoice.VIOLET -> fixed("1E1830", "14101F", "B388FF", "E8E2F2", "A296BF")
            ThemeChoice.FOREST -> fixed("15211A", "0D140F", "66BB6A", "E2EDE4", "8FAE96")
            ThemeChoice.ROSE -> fixed("241620", "170E12", "FF6E8E", "F2E2E8", "BD93A4")
        }

    private fun dynamicPalettes(context: Context): WidgetPalettes {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // No wallpaper colours below Android 12, and no light palette of our own
            // to offer — same fallback the app makes.
            return palettesFor(context, ThemeChoice.LIQUID_WAVE)
        }
        fun color(resId: Int) = ContextCompat.getColor(context, resId)
        // Higher tone number = darker, so the light variant mirrors the dark one
        // rather than repeating it.
        return WidgetPalettes(
            day = WidgetPalette(
                surface = color(android.R.color.system_neutral1_50),
                artBackground = color(android.R.color.system_neutral1_100),
                accent = color(android.R.color.system_accent1_600),
                textPrimary = color(android.R.color.system_neutral1_900),
                textSecondary = color(android.R.color.system_neutral2_700),
            ),
            night = WidgetPalette(
                surface = color(android.R.color.system_neutral1_800),
                artBackground = color(android.R.color.system_neutral1_900),
                accent = color(android.R.color.system_accent1_200),
                textPrimary = color(android.R.color.system_neutral1_50),
                textSecondary = color(android.R.color.system_neutral2_200),
            ),
        )
    }

    private fun fixed(
        surface: String,
        background: String,
        accent: String,
        textPrimary: String,
        textSecondary: String,
    ): WidgetPalettes {
        val palette = WidgetPalette(
            surface = Color.parseColor("#$surface"),
            artBackground = Color.parseColor("#$background"),
            accent = Color.parseColor("#$accent"),
            textPrimary = Color.parseColor("#$textPrimary"),
            textSecondary = Color.parseColor("#$textSecondary"),
        )
        return WidgetPalettes(day = palette, night = palette)
    }
}
