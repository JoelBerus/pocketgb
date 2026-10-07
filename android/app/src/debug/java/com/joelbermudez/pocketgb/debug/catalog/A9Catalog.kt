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
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.saves.ResumeFailure
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.components.RenameGameDialog
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.details.GameSettingsSheet
import com.joelbermudez.pocketgb.ui.gameplay.ResumeFailedDialog
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.LibraryContent
import com.joelbermudez.pocketgb.ui.library.LocalInitialMenuFor
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme

private typealias CatalogScreen = @Composable (DebugIntent) -> Unit

/** Alias de muestra (A9): el primer juego renombrado, con su huella conocida. */
private const val DEMO_ALIAS = "Rojo de Joel"

private fun renamedPrefs(): LibraryPreferencesData = CatalogData.prefs().setAlias(CatalogData.games[0], DEMO_ALIAS)

private fun detailsOf(entry: RomEntry) = GameDetails(
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

/** Los juegos recientes (los tres primeros) tienen «Continuar» exacto en el catálogo. */
private val resumableIds = CatalogData.games.take(3).map { it.id }.toSet()

private val a9Actions = GameActions(
    onOpenDetails = {},
    onToggleFavorite = {},
    onHide = {},
    onPlay = {},
    onGameSettings = {},
    onPlayFromStart = {},
    onRename = {},
    canResume = { it.id in resumableIds },
)

@Composable
private fun A9Library(prefs: LibraryPreferencesData, menuFor: String? = null) {
    CompositionLocalProvider(LocalInitialMenuFor provides menuFor) {
        LibraryContent(
            state = LibraryState.Ready(CatalogData.games, "Juegos"),
            prefs = prefs,
            query = "",
            filter = LibraryFilter.ALL,
            onQueryChange = {},
            onFilterChange = {},
            onLayoutChange = {},
            onSortChange = {},
            onChooseFolder = {},
            onRescan = {},
            actions = a9Actions,
            artworkFingerprints = CatalogData.coveredFingerprints,
        )
    }
}

@Composable
private fun A9Details(prefs: LibraryPreferencesData, canResume: Boolean, menuOpen: Boolean = false) {
    val entry = prefs.withAlias(CatalogData.games[0])
    GameDetailsContent(
        entry = entry,
        load = DetailsLoad.Loaded(detailsOf(entry)),
        favorite = true,
        lastPlayedAt = System.currentTimeMillis() - 3_600_000L,
        onPlay = {},
        onToggleFavorite = {},
        onHide = {},
        onBack = {},
        fingerprint = CatalogData.fingerprint(entry.id),
        onOpenSettings = {},
        canResume = canResume,
        onPlayFromStart = {},
        onRename = {},
        initialMenuOpen = menuOpen,
    )
}

/** Pantallas nuevas de A9: renombrar y continuación exacta («Continuar» / «Jugar desde el inicio»). */
internal val a9CatalogScreens: Map<String, CatalogScreen> = buildMap {
    // Detalle con «Continuar» exacto y «Jugar desde el inicio» (estado automático vigente), juego renombrado.
    put("details-resume-exact") { A9Details(remember { renamedPrefs() }, canResume = true) }
    // Diálogo de renombrar sobre el detalle, con el alias actual seleccionado (sin teclado: no pide el foco).
    put("details-rename") {
        val prefs = remember { renamedPrefs() }
        Box(Modifier.fillMaxSize()) {
            A9Details(prefs, canResume = true)
            RenameGameDialog(
                entry = prefs.withAlias(CatalogData.games[0]),
                currentAlias = DEMO_ALIAS,
                onSave = {},
                onDismiss = {},
                requestFocus = false,
            )
        }
    }
    // Menú de la barra superior del detalle abierto («Renombrar»).
    put("details-more-menu") { A9Details(remember { renamedPrefs() }, canResume = true, menuOpen = true) }
    // Menú contextual de una tarjeta con «Continuar», «Jugar desde el inicio» y «Renombrar».
    put("game-context-menu-resume") {
        // «DEMO ADVENTURE»: jugado hace poco (con «Continuar» exacto) y en la primera fila de la cuadrícula.
        A9Library(remember { renamedPrefs() }, menuFor = CatalogData.games[2].id)
    }
    // Biblioteca con un juego renombrado: el alias se ve en el carril y en la cuadrícula, y ordena por él.
    put("library-renamed") { A9Library(remember { renamedPrefs() }) }
    // Ajustes del juego con la fila «Nombre» (alias y título del cartucho) y «Renombrar».
    put("game-settings-rename") {
        val prefs = remember { renamedPrefs() }
        Box(Modifier.fillMaxSize()) {
            A9Library(prefs)
            GameSettingsSheet(
                title = DEMO_ALIAS,
                isColor = false,
                global = GameplaySettingsData(),
                overrides = GameOverrides(),
                onOverridesChange = {},
                onDismiss = {},
                headerTitle = CatalogData.games[0].title,
                onRename = {},
            )
        }
    }
    // «No se pudo continuar»: el juego guardó después del estado automático. Los diálogos de partida van siempre oscuros
    // (K4, `GameplayRoot`), aunque aparezcan sobre el detalle.
    put("resume-failed") {
        Box(Modifier.fillMaxSize()) {
            A9Details(remember { renamedPrefs() }, canResume = true)
            PocketGBTheme(forceDark = true, dynamicColor = false) {
                ResumeFailedDialog(ResumeFailure.NOT_CURRENT, onPlayFromStart = {}, onDismiss = {})
            }
        }
    }
}
