package com.joelbermudez.pocketgb.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.joelbermudez.pocketgb.settings.AppearanceRepository
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.ui.about.AboutScreen
import com.joelbermudez.pocketgb.ui.details.GameDetailsScreen
import com.joelbermudez.pocketgb.ui.favorites.FavoritesScreen
import com.joelbermudez.pocketgb.ui.library.LibraryScreen
import com.joelbermudez.pocketgb.ui.settings.AppearanceScreen
import com.joelbermudez.pocketgb.ui.settings.LibrarySettingsScreen
import com.joelbermudez.pocketgb.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private data class NavigationItem(
    val destination: TopLevelDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

private val navigationItems = listOf(
    NavigationItem(TopLevelDestination.LIBRARY, "Biblioteca", Icons.Filled.VideogameAsset, Icons.Outlined.VideogameAsset),
    NavigationItem(TopLevelDestination.FAVORITES, "Favoritos", Icons.Filled.Star, Icons.Outlined.StarBorder),
    NavigationItem(TopLevelDestination.SETTINGS, "Ajustes", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun PocketGBApp(
    appearance: AppearanceState,
    appearanceRepository: AppearanceRepository,
    library: LibraryViewModel,
) {
    val navigationState = rememberSaveable(saver = AppNavigationState.Saver) { AppNavigationState() }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = navigationState.currentBackStack.size > 1) {
        navigationState.pop()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                navigationItems.forEach { item ->
                    val selected = navigationState.selected == item.destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = dropUnlessResumed { navigationState.select(item.destination) },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        // Los insets del sistema y de la barra inferior se consumen aquí una sola vez;
        // las pantallas hijas no los vuelven a aplicar.
        Box(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
            NavDisplay(
                backStack = navigationState.currentBackStack,
                onBack = { navigationState.pop() },
                entryProvider = { route ->
                    when (route) {
                        LibraryRoute.Root -> NavEntry(route) {
                            LibraryScreen(
                                viewModel = library,
                                onOpenDetails = { navigationState.push(LibraryRoute.Details(it)) },
                            )
                        }
                        is LibraryRoute.Details -> NavEntry(route) {
                            GameDetailsScreen(library, route.gameId, onBack = { navigationState.pop() })
                        }
                        FavoritesRoute.Root -> NavEntry(route) {
                            FavoritesScreen(
                                viewModel = library,
                                onOpenDetails = { navigationState.push(FavoritesRoute.Details(it)) },
                            )
                        }
                        is FavoritesRoute.Details -> NavEntry(route) {
                            GameDetailsScreen(library, route.gameId, onBack = { navigationState.pop() })
                        }
                        SettingsRoute.Root -> NavEntry(route) {
                            SettingsScreen(
                                onAppearance = { navigationState.push(SettingsRoute.Appearance) },
                                onLibrary = { navigationState.push(SettingsRoute.Library) },
                                onAbout = { navigationState.push(SettingsRoute.About) },
                            )
                        }
                        SettingsRoute.Appearance -> NavEntry(route) {
                            AppearanceScreen(
                                appearance = appearance,
                                onThemeModeChange = { mode ->
                                    scope.launch { appearanceRepository.setThemeMode(mode) }
                                },
                                onDynamicColorChange = { enabled ->
                                    scope.launch { appearanceRepository.setDynamicColor(enabled) }
                                },
                                onBack = { navigationState.pop() },
                            )
                        }
                        SettingsRoute.Library -> NavEntry(route) {
                            LibrarySettingsScreen(library, onBack = { navigationState.pop() })
                        }
                        SettingsRoute.About -> NavEntry(route) {
                            AboutScreen(onBack = { navigationState.pop() })
                        }
                    }
                },
            )
        }
    }
}
