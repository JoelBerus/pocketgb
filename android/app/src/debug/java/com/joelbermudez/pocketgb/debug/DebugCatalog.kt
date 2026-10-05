package com.joelbermudez.pocketgb.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryError
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomProblem
import com.joelbermudez.pocketgb.ui.about.AboutScreen
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.favorites.FavoritesContent
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.settings.AppearanceScreen
import com.joelbermudez.pocketgb.ui.settings.LibrarySettingsContent
import com.joelbermudez.pocketgb.ui.settings.SettingsScreen

private fun demoEntry(
    id: String,
    title: String,
    isColor: Boolean,
    sizeBytes: Long,
    problem: RomProblem? = null,
) = RomEntry(
    id = id,
    uri = "content://demo/$id",
    fileName = id.substringAfterLast('/'),
    title = title,
    isColor = isColor,
    sizeBytes = sizeBytes,
    headerChecksumOk = problem == null,
    problem = problem,
)

internal val demoGames = listOf(
    demoEntry("Pokemon Red.gb", "POKÉMON RED", false, 1024L * 1024),
    demoEntry("Amarillo/Pokemon Yellow.gbc", "POKÉMON YELLOW", true, 1024L * 1024),
    demoEntry("Demo Adventure.gb", "DEMO ADVENTURE", false, 32L * 1024),
    demoEntry("Color Demo.gbc", "COLOR DEMO", true, 512L * 1024),
)

private val demoBroken = demoEntry("Roto.gb", "ROTO", false, 16, RomProblem.INVALID_HEADER)
private val demoFavorites = LibraryPreferencesData(favorites = setOf(demoGames[0].id, demoGames[1].id))
private val demoActions = GameActions({}, {}, {})

private fun demoDetails(entry: RomEntry) = GameDetails(
    entry = entry,
    cartridge = "MBC3 + RAM + batería",
    romBytes = 1024 * 1024,
    sramBytes = 32 * 1024,
    hasBattery = true,
    hasRtc = false,
    headerChecksumOk = true,
    globalChecksumOk = true,
    fingerprint = "9d4c1e07b3a85f26c0de91ab47f3825e6b10c9d7a2f45e83b6c1d09e7f2a4b58",
)

@Composable
internal fun DebugCatalog(intent: DebugIntent) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, intent.fontScale),
    ) {
        Box(Modifier.fillMaxSize().testTag("debug-screen-${intent.screen}")) {
            when (intent.screen) {
                "library-empty" -> DemoLibrary(LibraryState.NoFolder)
                "library-grid" -> DemoLibrary(LibraryState.Ready(demoGames, "Juegos"), demoFavorites)
                "library-list" -> DemoLibrary(
                    LibraryState.Ready(demoGames + demoBroken, "Juegos"),
                    demoFavorites.copy(layout = LibraryLayout.LIST),
                )
                "library-search" -> DemoLibrary(
                    LibraryState.Ready(demoGames, "Juegos"),
                    demoFavorites,
                    query = "zelda",
                )
                "library-error" -> DemoLibrary(LibraryState.Failed(LibraryError.PermissionRevoked))
                "library-loading" -> DemoLibrary(LibraryState.Loading)
                "library-access-error" -> DemoLibrary(LibraryState.Failed(LibraryError.AccessNotKept))
                "library-detail" -> GameDetailsContent(
                    entry = demoGames[0],
                    load = DetailsLoad.Loaded(demoDetails(demoGames[0])),
                    favorite = true,
                    lastPlayedAt = null,
                    onToggleFavorite = {},
                    onHide = {},
                    onBack = {},
                )
                "library-detail-problem" -> GameDetailsContent(
                    entry = demoBroken,
                    load = DetailsLoad.Loading,
                    favorite = false,
                    lastPlayedAt = null,
                    onToggleFavorite = {},
                    onHide = {},
                    onBack = {},
                )
                "favorites-empty" -> FavoritesContent(
                    LibraryState.Ready(demoGames, "Juegos"),
                    LibraryPreferencesData(),
                    demoActions,
                )
                "favorites" -> FavoritesContent(LibraryState.Ready(demoGames, "Juegos"), demoFavorites, demoActions)
                "settings-main" -> SettingsScreen(onAppearance = {}, onLibrary = {}, onAbout = {})
                "settings-library" -> LibrarySettingsContent(
                    state = LibraryState.Ready(demoGames, "Juegos"),
                    folderName = "Juegos",
                    hidden = listOf(demoGames[2], demoGames[3]),
                    onChooseFolder = {},
                    onRescan = {},
                    onForget = {},
                    onUnhide = {},
                    onBack = {},
                )
                "appearance" -> AppearanceScreen(intent.appearance, {}, {}, {})
                "about" -> AboutScreen(onBack = {})
                "native-video" -> NativeVideoScreen()
                "gameplay-controls" -> GameplayDebugScreen()
                "gameplay-fast-forward" -> GameplayDebugScreen(initialSpeed = 4)
                else -> UnknownScreen(intent.screen)
            }
        }
    }
}

@Composable
private fun DemoLibrary(
    state: LibraryState,
    prefs: LibraryPreferencesData = LibraryPreferencesData(),
    query: String = "",
) {
    LibraryContent(
        state = state,
        prefs = prefs,
        query = query,
        filter = LibraryFilter.ALL,
        onQueryChange = {},
        onFilterChange = {},
        onLayoutChange = {},
        onSortChange = {},
        onChooseFolder = {},
        onRescan = {},
        actions = demoActions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnknownScreen(id: String) {
    Scaffold(topBar = { TopAppBar(title = { Text("Pantalla desconocida") }) }) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(id, modifier = Modifier.testTag("unknown-screen-id"))
        }
    }
}
