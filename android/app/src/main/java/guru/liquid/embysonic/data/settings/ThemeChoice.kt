package guru.liquid.embysonic.data.settings

/**
 * User-selectable app colour theme. [DYNAMIC] follows the Android system
 * (Material You) wallpaper colours on Android 12+; the rest are fixed dark
 * palettes. [label] is the user-facing name shown in Settings.
 */
enum class ThemeChoice(val label: String) {
    LIQUID_WAVE("liquidWave"),
    DYNAMIC("Dynamic"),
    EMBER("Ember"),
    VIOLET("Violet"),
    FOREST("Forest"),
    ROSE("Rose"),
    ;

    companion object {
        val DEFAULT = LIQUID_WAVE

        fun fromKey(key: String?): ThemeChoice =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
