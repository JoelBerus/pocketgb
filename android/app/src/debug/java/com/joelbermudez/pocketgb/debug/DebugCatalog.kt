package com.joelbermudez.pocketgb.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryError
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.debug.catalog.CatalogArtwork
import com.joelbermudez.pocketgb.debug.catalog.a7Aliases
import com.joelbermudez.pocketgb.debug.catalog.a7CatalogScreens
import com.joelbermudez.pocketgb.debug.catalog.a7ComposedScreens
import com.joelbermudez.pocketgb.debug.catalog.a9CatalogScreens
import com.joelbermudez.pocketgb.debug.catalog.GameplayCatalogScreen
import com.joelbermudez.pocketgb.debug.catalog.GameplayFrame
import com.joelbermudez.pocketgb.debug.catalog.gameplayCatalogScreens
import com.joelbermudez.pocketgb.debug.catalog.libraryCatalogScreens
import com.joelbermudez.pocketgb.debug.catalog.n2CatalogScreens
import com.joelbermudez.pocketgb.debug.catalog.settingsCatalogScreens
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
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

/** IDs que se ven sobre el juego: siempre oscuros y a pantalla completa (K4, K5), una sola variante de tema. */
private val darkGameScreens = setOf(
    "gameplay-controls", "gameplay-fast-forward", "pause-sheet", "pause-dialog", "states-sheet", "states-dialog",
    "exit-save-failed", "exit-risk", "save-warning", "open-error", "save-problem", "states-rescue",
)

private val legacyScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap<String, @Composable (DebugIntent) -> Unit> {
    put("library-empty") { DemoLibrary(LibraryState.NoFolder) }
    put("library-list") {
        DemoLibrary(LibraryState.Ready(demoGames + demoBroken, "Juegos"), demoFavorites.copy(layout = LibraryLayout.LIST))
    }
    put("library-error") { DemoLibrary(LibraryState.Failed(LibraryError.PermissionRevoked)) }
    put("library-loading") { DemoLibrary(LibraryState.Loading) }
    put("library-access-error") { DemoLibrary(LibraryState.Failed(LibraryError.AccessNotKept)) }
    put("library-detail-played") {
        GameDetailsContent(
            entry = demoGames[0],
            load = DetailsLoad.Loaded(demoDetails(demoGames[0])),
            favorite = false,
            lastPlayedAt = 1_759_700_000_000,
            onPlay = {},
            onToggleFavorite = {},
            onHide = {},
            onBack = {},
        )
    }
    put("library-detail-problem") {
        GameDetailsContent(
            entry = demoBroken,
            load = DetailsLoad.Loading,
            favorite = false,
            lastPlayedAt = null,
            onPlay = {},
            onToggleFavorite = {},
            onHide = {},
            onBack = {},
        )
    }
    put("favorites-empty") {
        FavoritesContent(LibraryState.Ready(demoGames, "Juegos"), LibraryPreferencesData(), demoActions)
    }
    put("settings-main") { SettingsScreen(onAppearance = {}, onLibrary = {}, onSaves = {}, onAbout = {}) }
    put("settings-library") {
        LibrarySettingsContent(
            state = LibraryState.Ready(demoGames, "Juegos"),
            folderName = "Juegos",
            hidden = listOf(demoGames[2], demoGames[3]),
            onChooseFolder = {},
            onRescan = {},
            onForget = {},
            onUnhide = {},
            onBack = {},
        )
    }
    put("appearance") { i -> AppearanceScreen(i.appearance, {}, {}, {}) }
    put("about") { AboutScreen(onBack = {}) }
    put("pause-sheet") { PauseSheetCatalog(landscape = false) }
    put("pause-dialog") { PauseSheetCatalog(landscape = true) }
    put("states-sheet") { StatesSheetCatalog(landscape = false) }
    put("states-dialog") { StatesSheetCatalog(landscape = true) }
    put("exit-save-failed") { ExitSaveFailedCatalog(risk = false) }
    put("exit-risk") { ExitSaveFailedCatalog(risk = true) }
    put("save-problem") { SaveProblemCatalog() }
    put("states-rescue") { StatesRescueCatalog() }
    put("save-warning") { SaveWarningCatalog() }
    put("open-error") { OpenErrorCatalog() }
    put("saves-settings") { SavesSettingsCatalog() }
    put("native-video") { NativeVideoScreen() }
    put("gameplay-controls") { GameplayCatalogScreen(GameplaySettingsData()) }
    put("gameplay-fast-forward") { GameplayCatalogScreen(GameplaySettingsData(), initialSpeed = 4) }
}.mapValues { (id, content) ->
    if (id in darkGameScreens) {
        val framed: @Composable (DebugIntent) -> Unit = { i -> GameplayFrame { content(i) } }
        framed
    } else {
        content
    }
}

/** Registro único de pantallas del catálogo: el test de cobertura lo cruza con `tools/android-screens.txt`. */
private val a6CatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> =
    legacyScreens + libraryCatalogScreens + settingsCatalogScreens + gameplayCatalogScreens

internal val catalogScreens: Map<String, @Composable (DebugIntent) -> Unit> =
    a6CatalogScreens + a7CatalogScreens + a7Aliases.mapValues { (_, target) -> a6CatalogScreens.getValue(target) } +
        a7ComposedScreens(a6CatalogScreens) + a9CatalogScreens + n2CatalogScreens

@Composable
internal fun DebugCatalog(intent: DebugIntent) {
    // La escala de fuente y las señales de accesibilidad se aplican en `buildVariantContent`, fuera de `PocketGBTheme`.
    Box(Modifier.fillMaxSize().testTag("debug-screen-${intent.screen}")) {
        CatalogArtwork {
            val screen = catalogScreens[intent.screen]
            if (screen != null) screen(intent) else UnknownScreen(intent.screen)
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
