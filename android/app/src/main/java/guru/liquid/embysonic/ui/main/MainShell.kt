package guru.liquid.embysonic.ui.main

import androidx.compose.foundation.layout.Column
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
import guru.liquid.embysonic.ui.library.DetailScreen
import guru.liquid.embysonic.ui.library.LibraryScreen
import guru.liquid.embysonic.ui.mixes.MixesScreen
import guru.liquid.embysonic.ui.nav.Routes
import guru.liquid.embysonic.ui.playback.NowPlayingScreen

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
    val current by navController.currentBackStackEntryAsState()
    val currentRoute = current?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute != Routes.NOW_PLAYING) {
                Column {
                    MiniPlayerBar(onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) })
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == Routes.HOME,
                            onClick = {
                                if (currentRoute == Routes.DETAIL) {
                                    navController.popBackStack(Routes.HOME, inclusive = false).also { found ->
                                        if (!found) navController.navigateRootTab(Routes.HOME)
                                    }
                                } else if (currentRoute == Routes.HOME) {
                                    navController.navigateRootTab(Routes.HOME)
                                } else {
                                    navController.navigateTab(Routes.HOME)
                                }
                            },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home") },
                        )

                        libraries.forEach { library ->
                            val libraryRoute = Routes.library(library.id, library.kind.name)
                            val libraryPattern = Routes.libraryPattern(library.kind.name)
                            NavigationBarItem(
                                selected = currentRoute == libraryPattern,
                                onClick = {
                                    if (currentRoute == Routes.DETAIL) {
                                        navController.popBackStack(libraryPattern, inclusive = false).also { found ->
                                            if (!found) navController.navigateRootTab(libraryRoute)
                                        }
                                    } else if (currentRoute == libraryPattern) {
                                        navController.navigateRootTab(libraryRoute)
                                    } else {
                                        navController.navigateTab(libraryRoute)
                                    }
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
                }
            }
        },
    ) { padding ->
        // Pushes a drill-down level onto the shell back stack (bottom nav stays visible).
        val openDetail = { id: String, title: String, kind: guru.liquid.embysonic.data.emby.DetailKind ->
            navController.navigate(Routes.detail(id, kind.name, title))
        }

        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSettings = onOpenSettings,
                    onOpenItem = openDetail,
                    onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                    contentPadding = padding,
                )
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
                LibraryScreen(
                    contentPadding = padding,
                    onOpenItem = openDetail,
                    onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                )
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
                LibraryScreen(
                    contentPadding = padding,
                    onOpenItem = openDetail,
                    onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                )
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(
                    navArgument(Routes.ARG_ITEM_ID) { type = NavType.StringType },
                    navArgument(Routes.ARG_DETAIL_KIND) { type = NavType.StringType },
                    navArgument(Routes.ARG_TITLE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) {
                DetailScreen(
                    contentPadding = padding,
                    onBack = { navController.popBackStack() },
                    onOpenItem = openDetail,
                    onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                )
            }
            composable(Routes.MIXES) {
                MixesScreen(
                    contentPadding = padding,
                    onOpenItem = openDetail,
                    onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                )
            }
            composable(Routes.NOW_PLAYING) {
                NowPlayingScreen(onCollapse = { navController.popBackStack() })
            }
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

private fun androidx.navigation.NavController.navigateRootTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
        restoreState = false
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
