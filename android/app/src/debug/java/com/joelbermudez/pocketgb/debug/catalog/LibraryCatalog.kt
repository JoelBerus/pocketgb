package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.GameDetails
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.components.HideGameDialog
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.details.GameSettingsSheet
import com.joelbermudez.pocketgb.ui.favorites.FavoritesContent
import com.joelbermudez.pocketgb.ui.gameplay.OpeningOverlay
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.library.LocalAutoFocusSearch
import com.joelbermudez.pocketgb.ui.library.LocalInitialMenuFor

// A9: «Renombrar» está en el menú contextual de la app; el catálogo lo muestra igual. N3 (ND15): el carril solo muestra
// juegos que se pueden continuar; los tres recientes del catálogo lo son.
private val actions = GameActions(
    {}, {}, {}, onPlay = {}, onGameSettings = {}, onRename = {},
    canResume = { CatalogData.fingerprint(it.id) in CatalogData.coveredFingerprints },
)

@Composable
private fun CatalogLibrary(
    state: LibraryState,
    prefs: LibraryPreferencesData,
    query: String = "",
    filter: LibraryFilter = LibraryFilter.ALL,
    newGamesSummary: Int = 0,
    autoFocusSearch: Boolean = false,
    menuFor: String? = null,
) {
    CompositionLocalProvider(LocalAutoFocusSearch provides autoFocusSearch, LocalInitialMenuFor provides menuFor) {
        LibraryContent(
            state = state,
            prefs = prefs,
            query = query,
            filter = filter,
            onQueryChange = {},
            onFilterChange = {},
            onLayoutChange = {},
            onSortChange = {},
            onChooseFolder = {},
            onRescan = {},
            actions = actions,
            newGamesSummary = newGamesSummary,
            artworkFingerprints = CatalogData.coveredFingerprints,
        )
    }
}

private fun details(entry: RomEntry) = GameDetails(
    entry = entry,
    cartridge = "MBC3 + RAM + batería",
    romBytes = 1024 * 1024,
    sramBytes = 32 * 1024,
    hasBattery = true,
    hasRtc = false,
    headerChecksumOk = true,
    globalChecksumOk = true,
    fingerprint = CatalogData.fingerprint(entry.id),
)

private val ready get() = LibraryState.Ready(CatalogData.games, "Juegos")

/** Pantallas de Biblioteca, Búsqueda, Detalle y Favoritos de A6 (L3). */
internal val libraryCatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    put("launch") { CatalogLibrary(ready, remember { CatalogData.prefs() }) }
    put("library-folder-empty") { CatalogLibrary(LibraryState.Ready(emptyList(), "Juegos"), LibraryPreferencesData()) }
    put("library-cloud-pending") {
        CatalogLibrary(LibraryState.Ready(CatalogData.games + CatalogData.cloudPending, "Juegos"), remember { CatalogData.prefs() })
    }
    put("library-cloud-downloading") {
        Box(Modifier.fillMaxSize()) {
            CatalogLibrary(LibraryState.Ready(CatalogData.games + CatalogData.cloudPending, "Juegos"), remember { CatalogData.prefs() })
            OpeningOverlay()
        }
    }
    put("library-scan-progress") {
        CatalogLibrary(LibraryState.Scanning(CatalogData.games, "Juegos", done = 3, total = 8), remember { CatalogData.prefs() })
    }
    put("library-scan-summary") {
        val games = CatalogData.games.mapIndexed { index, game -> if (index >= 6) game.copy(isNew = true) else game }
        CatalogLibrary(LibraryState.Ready(games, "Juegos"), remember { CatalogData.prefs() }, newGamesSummary = 2)
    }
    put("library-continue") {
        CatalogLibrary(ready, remember { CatalogData.prefs(favorites = emptySet()) })
    }
    put("library-grid") {
        CatalogLibrary(
            LibraryState.Ready(CatalogData.games + CatalogData.broken, "Juegos"),
            remember { CatalogData.prefs() },
        )
    }
    // La misma biblioteca desplazada por el script (`swipe=up`): deja ver las tarjetas, favoritos y el juego con problema.
    put("library-grid-scrolled") {
        CatalogLibrary(
            LibraryState.Ready(CatalogData.games + CatalogData.broken, "Juegos"),
            remember { CatalogData.prefs() },
        )
    }
    put("search-active") { CatalogLibrary(ready, remember { CatalogData.prefs() }, autoFocusSearch = true) }
    put("search-results") { i -> CatalogLibrary(ready, remember { CatalogData.prefs() }, query = i.query ?: "te") }
    put("library-search") { i ->
        CatalogLibrary(ready, remember { CatalogData.prefs() }, query = i.query ?: "zelda", filter = LibraryFilter.GBC)
    }
    put("game-context-menu") {
        val prefs = remember { CatalogData.prefs() }
        val first = remember { LibraryQuery.visible(CatalogData.games, prefs, LibraryFilter.ALL, "").first().id }
        CatalogLibrary(ready, prefs, menuFor = first)
    }
    put("remove-game-confirm") {
        val prefs = remember { CatalogData.prefs() }
        Box(Modifier.fillMaxSize()) {
            CatalogLibrary(ready, prefs)
            HideGameDialog(title = CatalogData.games[3].title, onConfirm = {}, onDismiss = {})
        }
    }
    put("game-settings") {
        val prefs = remember { CatalogData.prefs() }
        Box(Modifier.fillMaxSize()) {
            CatalogLibrary(ready, prefs)
            GameSettingsSheet(
                title = CatalogData.games[0].title,
                console = com.joelbermudez.pocketgb.library.RomConsole.GB,
                global = GameplaySettingsData(),
                overrides = GameOverrides(colorForGameBoy = true, compatPalette = 5),
                onOverridesChange = {},
                onDismiss = {},
            )
        }
    }
    put("library-detail") {
        val entry = CatalogData.games[0]
        GameDetailsContent(
            entry = entry,
            load = DetailsLoad.Loaded(details(entry)),
            favorite = true,
            lastPlayedAt = System.currentTimeMillis() - 3_600_000L,
            onPlay = {},
            onToggleFavorite = {},
            onHide = {},
            onBack = {},
            fingerprint = CatalogData.fingerprint(entry.id),
            onOpenSettings = {},
            onRename = {},
        )
    }
    put("favorites") { FavoritesContent(ready, remember { CatalogData.prefs() }, actions) }
}
