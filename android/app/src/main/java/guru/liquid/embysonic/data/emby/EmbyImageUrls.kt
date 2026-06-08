package guru.liquid.embysonic.data.emby

import guru.liquid.embysonic.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds Emby image URLs (DTO-independent — needs only the item id + image tag).
 * Emby serves images at `{server}/Items/{id}/Images/{type}` and these URLs are
 * unauthenticated, so they can be handed straight to Coil. The `tag` busts the
 * cache when artwork changes.
 */
@Singleton
class EmbyImageUrls @Inject constructor(
    private val settings: SettingsRepository,
) {
    private fun base(): String? = settings.snapshot().serverUrl?.trimEnd('/')

    fun primary(itemId: String, tag: String? = null, maxHeight: Int = 512): String? {
        val server = base() ?: return null
        val tagParam = tag?.let { "&tag=$it" } ?: ""
        return "$server/Items/$itemId/Images/Primary?maxHeight=$maxHeight&quality=90$tagParam"
    }
}
