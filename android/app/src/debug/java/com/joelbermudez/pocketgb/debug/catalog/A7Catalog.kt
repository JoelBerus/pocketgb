package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.joelbermudez.pocketgb.app.AppNavigationState
import com.joelbermudez.pocketgb.app.AppScaffold
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.input.PadAction
import com.joelbermudez.pocketgb.ui.gameplay.touchSettingsFor
import com.joelbermudez.pocketgb.ui.library.LibraryListDetail
import com.joelbermudez.pocketgb.ui.settings.ControllerMappingContent

private typealias CatalogScreen = @Composable (DebugIntent) -> Unit

/**
 * Pantallas nuevas de A7 (L4). El mando se simula con `controller=1`; fuente grande, contraste y reducir movimiento
 * los aplica `buildVariantContent` (fuera de `PocketGBTheme`) a partir de los argumentos, así que muchas IDs son la
 * misma pantalla de A6 con otros argumentos: [a7Aliases] las enlaza.
 */
internal val a7CatalogScreens: Map<String, CatalogScreen> = buildMap {
    // El juego siempre es oscuro y a pantalla completa (K4, K5); sin la barra de ajustes de A6, igual que en la partida.
    put("gameplay-controller") { i ->
        GameplayFrame { GameplayCatalogScreen(touchSettingsFor(i.gameplaySettings(), i.controller && !i.showTouch), i.rom) }
    }
    put("gameplay-controller-touch") { i ->
        GameplayFrame { GameplayCatalogScreen(touchSettingsFor(i.gameplaySettings(), i.controller && !i.showTouch), i.rom) }
    }
    put("settings-controller-mapping") { i ->
        ControllerMappingContent(i.gameplaySettings(), onUpdate = {}, onBack = {})
    }
    put("controller-assign-dialog") { i ->
        ControllerMappingContent(i.gameplaySettings(), onUpdate = {}, onBack = {}, initialAssigning = PadAction.A)
    }
}

/** ID de A7 → pantalla de A6 que dibuja (cambian los argumentos del manifiesto, no la composición). */
internal val a7Aliases: Map<String, String> = mapOf(
    "library-ax5" to "library-grid",
    "library-detail-ax5" to "library-detail",
    "settings-ax5" to "settings-main",
    "library-high-contrast" to "library-grid",
    "gameplay-high-contrast" to "gameplay-controls",
    "gameplay-landscape-high-contrast" to "gameplay-controls",
    "pause-sheet-ax5" to "pause-sheet",
    "gameplay-cutout-landscape" to "gameplay-landscape",
)

/** Pantallas de A7 que se montan sobre otras ya registradas (necesitan el mapa base para resolverlas). */
internal fun a7ComposedScreens(base: Map<String, CatalogScreen>): Map<String, CatalogScreen> = buildMap {
    // La biblioteca dentro del marco real de la app: con la ventana ancha (`window=wide`) pinta el NavigationRail.
    put("library-wide-rail") { i ->
        val navigation = remember { AppNavigationState() }
        AppScaffold(navigation) { base.getValue("library-grid")(i) }
    }
    // Estructura lista-detalle (R14): sigue tras `ENABLE_LIST_DETAIL`, aquí se ve montada con el detalle abierto.
    put("library-list-detail") { i ->
        LibraryListDetail(
            list = { base.getValue("library-grid")(i) },
            detail = { base.getValue("library-detail")(i) },
            initialDetailId = CatalogData.games[0].id,
        )
    }
}
