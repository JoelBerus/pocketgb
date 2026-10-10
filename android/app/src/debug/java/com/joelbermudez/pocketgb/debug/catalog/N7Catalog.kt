package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.joelbermudez.pocketgb.app.AppNavigationState
import com.joelbermudez.pocketgb.app.AppScaffold
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.debug.GameBackdrop
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.SavedGameUi
import com.joelbermudez.pocketgb.travel.SaveStatus
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.gameplay.SaveLoadWarningDialog
import com.joelbermudez.pocketgb.ui.settings.SavesSettingsContent
import com.joelbermudez.pocketgb.ui.travel.ChooseDialog
import com.joelbermudez.pocketgb.ui.travel.ImportedDialog
import com.joelbermudez.pocketgb.ui.travel.InboxDialog
import com.joelbermudez.pocketgb.ui.travel.TravelActions

/** N7 · partidas que viajan, con datos sintéticos: menú de exportar/importar, estado de la partida y diálogos. */
private val noTravel = TravelActions({}, {}, {}, {}, {}, {})

@Composable
private fun N7Details(menuOpen: Boolean, status: SaveStatus) {
    val navigation = remember { AppNavigationState() }
    val entry = N5Data.blocks
    AppScaffold(navigation) {
        GameDetailsContent(
            entry = entry,
            load = DetailsLoad.Loaded(N5Data.details(entry)),
            favorite = false,
            lastPlayedAt = System.currentTimeMillis() - 7_200_000L,
            onPlay = {},
            onToggleFavorite = {},
            onHide = {},
            onBack = {},
            fingerprint = N5Data.fingerprint(entry),
            canResume = true,
            onRename = {},
            initialMenuOpen = menuOpen,
            // Las capturas del estado bajan hasta la casilla «Partida» (bajo «Jugar desde el inicio»).
            initialInfoScroll = if (menuOpen) 0 else 500,
            onOpenMoments = {},
            travel = noTravel,
            saveStatus = status,
        )
    }
}

private val fromIphone get() = SaveStatus("iPhone de Joel", "ios", System.currentTimeMillis() - 2 * 3_600_000L)

internal val n7CatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    put("n7-details-menu") { N7Details(menuOpen = true, status = fromIphone) }
    put("n7-details-status") { N7Details(menuOpen = false, status = fromIphone) }
    put("n7-details-status-here") { N7Details(menuOpen = false, status = SaveStatus(null, "android", System.currentTimeMillis() - 600_000L)) }
    put("n7-imported-continue") { _ ->
        GameBackdrop {
            ImportedDialog(
                "Se instaló la partida. La que había queda en Ajustes › Partidas.", "iPhone de Joel", onContinue = {}, onDismiss = {},
            )
        }
    }
    put("n7-choose") { _ -> GameBackdrop { ChooseDialog("iPhone de Joel", {}, {}, {}) } }
    put("n7-inbox") { _ -> GameBackdrop { InboxDialog("iPhone de Joel", "BLOQUES", {}, {}) } }
    put("n7-warning-divergence") { _ -> GameBackdrop { SaveLoadWarningDialog(SaveLoadWarning.Divergence("c1")) {} } }
    put("n7-warning-external") { _ -> GameBackdrop { SaveLoadWarningDialog(SaveLoadWarning.ExternalChange(readOnly = true)) {} } }
    put("n7-saves-conflicts") { _ ->
        val games = remember {
            listOf(
                SavedGameUi(
                    "9d4c1e07".repeat(8), "POKÉMON RED", "Pokemon Red.gb",
                    listOf(SaveStore.BackupInfo(1, 1_759_700_000_000)),
                    providerConflicts = listOf(
                        SaveStore.ProviderConflict("Pokemon Red 2.sav", 1_759_710_000_000),
                        SaveStore.ProviderConflict("Pokemon Red (1).sav", null),
                    ),
                ),
            )
        }
        SavesSettingsContent(games, openFingerprint = null, snackbar = remember { SnackbarHostState() }, onRestore = { _, _ -> }, onBack = {})
    }
    // ND20 · respuesta a la auditoría (al final: los catálogos solo crecen).
    put("n7-divergence-ask") { _ ->
        GameBackdrop {
            com.joelbermudez.pocketgb.ui.gameplay.SaveDivergenceDialog(
                System.currentTimeMillis() - 3_600_000L, System.currentTimeMillis() - 600_000L, {}, {}, {},
            )
        }
    }
    put("n7-warning-mirror-older") { _ -> GameBackdrop { SaveLoadWarningDialog(SaveLoadWarning.MirrorOlderSetAside()) {} } }
    put("n7-known") { _ -> GameBackdrop { ChooseDialog("iPhone de Joel", {}, {}, {}, known = true) } }
    put("n7-raw-confirm") { _ ->
        GameBackdrop { com.joelbermudez.pocketgb.ui.travel.ConfirmImportDialog(com.joelbermudez.pocketgb.travel.SaveImporter.Ask.RAW_CONFIRM, "", "POKÉMON RED", {}, {}) }
    }
    put("n7-config-mismatch") { _ ->
        GameBackdrop {
            // ND21: ya no se pregunta ni se descarta el estado; el resultado dice qué ajuste cambiar.
            val res = androidx.compose.ui.platform.LocalResources.current
            val done = com.joelbermudez.pocketgb.travel.SaveImporter.Result.Done(
                com.joelbermudez.pocketgb.saves.SaveLineage.Incoming.ADVANCE, installed = true, continueFrom = "iPhone de Joel",
                configDifferences = listOf(com.joelbermudez.pocketgb.travel.PgbmConfig.Key.MODEL),
            )
            com.joelbermudez.pocketgb.ui.travel.ImportedDialog(com.joelbermudez.pocketgb.ui.travel.importedText(res, done), done.continueFrom, {}, {})
        }
    }
}
