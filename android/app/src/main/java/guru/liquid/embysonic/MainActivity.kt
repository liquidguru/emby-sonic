package guru.liquid.embysonic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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

    // Android 13+ blocks ALL notifications — including the Media3 playback
    // notification in the shade — until the user grants POST_NOTIFICATIONS.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val startLoggedIn = settings.snapshot().isLoggedIn
        setContent {
            EmbySonicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(startLoggedIn = startLoggedIn)
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
