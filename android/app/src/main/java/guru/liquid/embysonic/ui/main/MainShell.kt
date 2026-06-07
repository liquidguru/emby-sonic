package guru.liquid.embysonic.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import guru.liquid.embysonic.ui.home.HomeScreen
import guru.liquid.embysonic.ui.library.LibraryScreen
import guru.liquid.embysonic.ui.mixes.MixesScreen
import guru.liquid.embysonic.ui.nav.Routes

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Home),
    Tab(Routes.LIBRARY, "Library", Icons.Default.LibraryMusic),
    Tab(Routes.MIXES, "Mixes", Icons.AutoMirrored.Filled.QueueMusic),
)

/**
 * Bottom-navigation shell hosting the three primary tabs. Login and Settings live
 * on the outer nav graph; [onOpenSettings] bubbles up to it.
 */
@Composable
fun MainShell(onOpenSettings: () -> Unit) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val current by navController.currentBackStackEntryAsState()
                val currentRoute = current?.destination?.route
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier,
        ) {
            composable(Routes.HOME) { HomeScreen(onOpenSettings = onOpenSettings, contentPadding = padding) }
            composable(Routes.LIBRARY) { LibraryScreen(contentPadding = padding) }
            composable(Routes.MIXES) { MixesScreen(contentPadding = padding) }
        }
    }
}
