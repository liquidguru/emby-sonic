package guru.liquid.embysonic.ui.nav

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import guru.liquid.embysonic.ui.brand.LiquidWaveSplashScreen
import guru.liquid.embysonic.ui.login.LoginScreen
import guru.liquid.embysonic.ui.main.MainShell
import guru.liquid.embysonic.ui.settings.SettingsScreen
import kotlinx.coroutines.delay

@Composable
fun AppNavHost(startLoggedIn: Boolean) {
    var showingSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1200)
        showingSplash = false
    }

    if (showingSplash) {
        LiquidWaveSplashScreen()
        return
    }

    val navController = rememberNavController()
    val start = if (startLoggedIn) Routes.MAIN else Routes.LOGIN

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.MAIN) {
            MainShell(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}
