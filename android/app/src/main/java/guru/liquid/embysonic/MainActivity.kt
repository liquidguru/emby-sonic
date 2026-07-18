package guru.liquid.embysonic

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import guru.liquid.embysonic.cast.CastManager
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.ui.nav.AppNavHost
import guru.liquid.embysonic.ui.theme.EmbySonicTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var castManager: CastManager

    // No runtime POST_NOTIFICATIONS request. The only notification liquidWave posts
    // is the media playback one, which is a foreground-service notification and is
    // EXEMPT from POST_NOTIFICATIONS — it shows in the shade / lock screen / car
    // whether or not the permission is granted (verified on device: the notification
    // is live while the permission reads denied). Asking for it therefore changes
    // nothing today, so we don't. If a NON-foreground notification is ever added
    // (e.g. "download complete"), request the permission contextually at that point,
    // for that reason.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        castManager.initialize(this)
        val startLoggedIn = settings.snapshot().isLoggedIn
        setContent {
            val themeChoice by settings.themeChoice.collectAsStateWithLifecycle(
                initialValue = remember { settings.snapshot().themeChoice },
            )
            EmbySonicTheme(themeChoice = themeChoice) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(startLoggedIn = startLoggedIn)
                }
            }
        }
    }
}
