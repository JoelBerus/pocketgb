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
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.game.GameplayViewModel
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.saves.SavesBrowser
import com.joelbermudez.pocketgb.ui.gameplay.GameplayRoot
import com.joelbermudez.pocketgb.ui.settings.SavesScreen
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import com.joelbermudez.pocketgb.ui.about.AboutScreen
import com.joelbermudez.pocketgb.ui.details.GameDetailsScreen
import com.joelbermudez.pocketgb.ui.favorites.FavoritesScreen
import com.joelbermudez.pocketgb.ui.library.LibraryScreen
import com.joelbermudez.pocketgb.ui.settings.AppearanceScreen
import com.joelbermudez.pocketgb.ui.settings.AudioSettingsScreen
import com.joelbermudez.pocketgb.ui.settings.ControllerMappingScreen
import com.joelbermudez.pocketgb.ui.settings.ControlsSettingsScreen
import com.joelbermudez.pocketgb.ui.settings.DisplaySettingsScreen
import com.joelbermudez.pocketgb.ui.settings.EmulationSettingsScreen
import com.joelbermudez.pocketgb.ui.settings.LicensesScreen
import com.joelbermudez.pocketgb.ui.settings.StorageSettingsScreen
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
    gameplay: GameplayViewModel,
    gameplaySettings: GameplaySettingsRepository,
) {
    // El estado de navegación vive aquí (no en el Scaffold): al salir de una partida se vuelve al mismo sitio.
    val navigationState = rememberSaveable(saver = AppNavigationState.Saver) { AppNavigationState() }
    GameplayRoot(gameplay) {
        AppScaffold(navigationState, appearance, appearanceRepository, library, gameplay, gameplaySettings)
    }
}

@Composable
private fun AppScaffold(
    navigationState: AppNavigationState,
    appearance: AppearanceState,
    appearanceRepository: AppearanceRepository,
    library: LibraryViewModel,
    gameplay: GameplayViewModel,
    gameplaySettings: GameplaySettingsRepository,
) {
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
                                onPlay = gameplay::open,
                                gameplaySettings = gameplaySettings,
                            )
                        }
                        is LibraryRoute.Details -> NavEntry(route) {
                            GameDetailsScreen(
                                library, route.gameId, onPlay = gameplay::open, onBack = { navigationState.pop() },
                                gameplaySettings = gameplaySettings,
                            )
                        }
                        FavoritesRoute.Root -> NavEntry(route) {
                            FavoritesScreen(
                                viewModel = library,
                                onOpenDetails = { navigationState.push(FavoritesRoute.Details(it)) },
                                onPlay = gameplay::open,
                                gameplaySettings = gameplaySettings,
                            )
                        }
                        is FavoritesRoute.Details -> NavEntry(route) {
                            GameDetailsScreen(
                                library, route.gameId, onPlay = gameplay::open, onBack = { navigationState.pop() },
                                gameplaySettings = gameplaySettings,
                            )
                        }
                        SettingsRoute.Root -> NavEntry(route) {
                            SettingsScreen(
                                onEmulation = { navigationState.push(SettingsRoute.SettingsEmulation) },
                                onControls = { navigationState.push(SettingsRoute.SettingsControls) },
                                onAudio = { navigationState.push(SettingsRoute.SettingsAudio) },
                                onDisplay = { navigationState.push(SettingsRoute.SettingsDisplay) },
                                onStorage = { navigationState.push(SettingsRoute.SettingsStorage) },
                                onAppearance = { navigationState.push(SettingsRoute.Appearance) },
                                onLibrary = { navigationState.push(SettingsRoute.Library) },
                                onSaves = { navigationState.push(SettingsRoute.Saves) },
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
                        SettingsRoute.Saves -> NavEntry(route) {
                            val context = LocalContext.current
                            val browser = remember(context) { SavesBrowser(File(context.filesDir, "saves")) }
                            SavesScreen(browser, gameplay, onBack = { navigationState.pop() })
                        }
                        SettingsRoute.About -> NavEntry(route) {
                            AboutScreen(
                                onBack = { navigationState.pop() },
                                onLicenses = { navigationState.push(SettingsRoute.SettingsLicenses) },
                            )
                        }
                        SettingsRoute.SettingsControls -> NavEntry(route) {
                            ControlsSettingsScreen(
                                gameplaySettings,
                                onBack = { navigationState.pop() },
                                onController = { navigationState.push(SettingsRoute.SettingsController) },
                            )
                        }
                        SettingsRoute.SettingsController -> NavEntry(route) {
                            ControllerMappingScreen(gameplaySettings, onBack = { navigationState.pop() })
                        }
                        SettingsRoute.SettingsDisplay -> NavEntry(route) {
                            DisplaySettingsScreen(gameplaySettings, onBack = { navigationState.pop() })
                        }
                        SettingsRoute.SettingsEmulation -> NavEntry(route) {
                            EmulationSettingsScreen(gameplaySettings, onBack = { navigationState.pop() })
                        }
                        SettingsRoute.SettingsAudio -> NavEntry(route) {
                            AudioSettingsScreen(gameplaySettings, onBack = { navigationState.pop() })
                        }
                        SettingsRoute.SettingsStorage -> NavEntry(route) {
                            StorageSettingsScreen(onBack = { navigationState.pop() })
                        }
                        SettingsRoute.SettingsLicenses -> NavEntry(route) {
                            LicensesScreen(onBack = { navigationState.pop() })
                        }
                    }
                },
            )
        }
    }
}
