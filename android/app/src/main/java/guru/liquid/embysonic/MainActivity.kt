package guru.liquid.embysonic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.ui.nav.AppNavHost
import guru.liquid.embysonic.ui.theme.EmbySonicTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startLoggedIn = settings.snapshot().isLoggedIn
        setContent {
            EmbySonicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(startLoggedIn = startLoggedIn)
                }
            }
        }
    }
}
