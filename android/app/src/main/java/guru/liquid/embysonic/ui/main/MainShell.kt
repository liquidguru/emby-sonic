package guru.liquid.embysonic.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import guru.liquid.embysonic.data.emby.AudioLibrary
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.ui.home.HomeScreen
import guru.liquid.embysonic.ui.library.LibraryScreen
import guru.liquid.embysonic.ui.mixes.MixesScreen
import guru.liquid.embysonic.ui.nav.Routes

/**
 * Bottom-navigation shell. The middle tabs are built from the user's discovered
 * audio libraries (Music, Audiobooks), so the nav adapts to what they actually have.
 * Login and Settings live on the outer nav graph; [onOpenSettings] bubbles up.
 */
@Composable
fun MainShell(
    onOpenSettings: () -> Unit,
    shellViewModel: ShellViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val libraries by shellViewModel.libraries.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val current by navController.currentBackStackEntryAsState()
                val currentRoute = current?.destination?.route

                NavigationBarItem(
                    selected = currentRoute == Routes.HOME,
                    onClick = { navController.navigateTab(Routes.HOME) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                )

                libraries.forEach { library ->
                    NavigationBarItem(
                        selected = currentRoute == Routes.libraryPattern(library.kind.name),
                        onClick = {
                            navController.navigateTab(Routes.library(library.id, library.kind.name))
                        },
                        icon = { Icon(library.icon(), contentDescription = library.name) },
                        label = { Text(library.shortLabel()) },
                    )
                }

                NavigationBarItem(
                    selected = currentRoute == Routes.MIXES,
                    onClick = { navController.navigateTab(Routes.MIXES) },
                    icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Mixes") },
                    label = { Text("Mixes") },
                )
            }
        },
    ) { padding ->
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(onOpenSettings = onOpenSettings, contentPadding = padding)
            }
            composable(
                route = Routes.LIBRARY_MUSIC,
                arguments = listOf(
                    navArgument(Routes.ARG_LIBRARY_ID) { type = NavType.StringType },
                    navArgument(Routes.ARG_KIND) {
                        type = NavType.StringType
                        defaultValue = LibraryKind.MUSIC.name
                    },
                ),
            ) {
                LibraryScreen(contentPadding = padding)
            }
            composable(
                route = Routes.LIBRARY_AUDIOBOOKS,
                arguments = listOf(
                    navArgument(Routes.ARG_LIBRARY_ID) { type = NavType.StringType },
                    navArgument(Routes.ARG_KIND) {
                        type = NavType.StringType
                        defaultValue = LibraryKind.AUDIOBOOKS.name
                    },
                ),
            ) {
                LibraryScreen(contentPadding = padding)
            }
            composable(Routes.MIXES) { MixesScreen(contentPadding = padding) }
        }
    }
}

private fun androidx.navigation.NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun AudioLibrary.icon(): ImageVector = when (kind) {
    LibraryKind.MUSIC -> Icons.Default.LibraryMusic
    LibraryKind.AUDIOBOOKS -> Icons.AutoMirrored.Filled.MenuBook
}

private fun AudioLibrary.shortLabel(): String = when (kind) {
    LibraryKind.MUSIC -> "Music"
    LibraryKind.AUDIOBOOKS -> "Audiobooks"
}
