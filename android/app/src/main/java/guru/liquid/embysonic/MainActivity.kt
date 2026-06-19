package guru.liquid.embysonic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.appcompat.app.AppCompatActivity
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

    // Android 13+ blocks ALL notifications — including the Media3 playback
    // notification in the shade — until the user grants POST_NOTIFICATIONS.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
