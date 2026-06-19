package guru.liquid.embysonic.ui.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * The Cast (MediaRouteButton). It auto-hides when no cast routes are available,
 * so it only appears when there's something to cast to. MainActivity is an
 * AppCompatActivity (a FragmentActivity) with an AppCompat theme, which the
 * route picker/controller dialogs require.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MediaRouteButton(context).also { button ->
                runCatching { CastButtonFactory.setUpMediaRouteButton(context, button) }
            }
        },
    )
}
