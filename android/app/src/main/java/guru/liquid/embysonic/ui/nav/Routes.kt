package guru.liquid.embysonic.ui.nav

import android.net.Uri

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val EQUALIZER = "equalizer"

    // Tabs inside the main bottom-nav shell.
    const val HOME = "home"
    const val MIXES = "mixes"
    const val NOW_PLAYING = "now_playing"
    const val ADVENTURE = "adventure"

    // Search, with a mode arg selecting the scope set ("MUSIC" / "AUDIOBOOKS").
    const val ARG_SEARCH_MODE = "mode"
    const val SEARCH = "search?$ARG_SEARCH_MODE={$ARG_SEARCH_MODE}"

    fun search(mode: String): String = "search?$ARG_SEARCH_MODE=$mode"

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

    // Drill-down detail screen. One parameterised route serves every level
    // (artist→albums, album→tracks, author→books, book→chapters); stacking two
    // instances with different args is plain forward navigation, so it's safe —
    // the save/restore-state collision only bites bottom-nav tabs.
    const val ARG_ITEM_ID = "itemId"
    const val ARG_DETAIL_KIND = "detailKind"
    const val ARG_TITLE = "title"
    const val DETAIL =
        "detail?$ARG_ITEM_ID={$ARG_ITEM_ID}&$ARG_DETAIL_KIND={$ARG_DETAIL_KIND}&$ARG_TITLE={$ARG_TITLE}"

    /** Concrete detail route. [detailKind] is a [DetailKind] name; [title] is shown in the bar. */
    fun detail(itemId: String, detailKind: String, title: String): String =
        "detail?$ARG_ITEM_ID=${Uri.encode(itemId)}&$ARG_DETAIL_KIND=$detailKind&$ARG_TITLE=${Uri.encode(title)}"
}
