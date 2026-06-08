package guru.liquid.embysonic.ui.nav

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val SETTINGS = "settings"

    // Tabs inside the main bottom-nav shell.
    const val HOME = "home"
    const val MIXES = "mixes"

    // Library tabs. Music and audiobooks are SEPARATE nav destinations (distinct
    // route prefixes) so the bottom nav's save/restore-state — which keys by route
    // pattern — keeps them independent. The library id is carried as an argument.
    const val ARG_LIBRARY_ID = "id"
    const val ARG_KIND = "kind"
    const val LIBRARY_MUSIC = "library/music?$ARG_LIBRARY_ID={$ARG_LIBRARY_ID}&$ARG_KIND={$ARG_KIND}"
    const val LIBRARY_AUDIOBOOKS =
        "library/audiobooks?$ARG_LIBRARY_ID={$ARG_LIBRARY_ID}&$ARG_KIND={$ARG_KIND}"

    /** Concrete route for a discovered library. [kind] is a [LibraryKind] name. */
    fun library(libraryId: String, kind: String): String {
        val segment = if (kind == "AUDIOBOOKS") "audiobooks" else "music"
        return "library/$segment?$ARG_LIBRARY_ID=$libraryId&$ARG_KIND=$kind"
    }

    /** Route pattern (for nav-selection comparison) for a given library kind. */
    fun libraryPattern(kind: String): String =
        if (kind == "AUDIOBOOKS") LIBRARY_AUDIOBOOKS else LIBRARY_MUSIC
}
