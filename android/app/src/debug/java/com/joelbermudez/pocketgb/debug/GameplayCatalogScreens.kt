package com.joelbermudez.pocketgb.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.game.GameNotice
import com.joelbermudez.pocketgb.ui.gameplay.SaveProblemBanner
import com.joelbermudez.pocketgb.ui.gameplay.noticeText
import com.joelbermudez.pocketgb.saves.FramePng
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.SavedGameUi
import com.joelbermudez.pocketgb.game.OpenError
import com.joelbermudez.pocketgb.ui.gameplay.ExitSaveFailedDialog
import com.joelbermudez.pocketgb.ui.gameplay.OpenErrorDialog
import com.joelbermudez.pocketgb.ui.gameplay.PauseSheet
import com.joelbermudez.pocketgb.ui.gameplay.SaveLoadWarningDialog
import com.joelbermudez.pocketgb.ui.settings.SavesSettingsContent
import java.io.IOException

/** Fondo negro que hace de pantalla de juego detrás de las hojas y diálogos del catálogo. */
@Composable
private fun GameBackdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
}

/** Captura sintética de 160x144 (degradado + rejilla), idéntica en cada ejecución. */
private fun demoThumbnail(seed: Int): ByteArray? {
    val pixels = IntArray(160 * 144) { i ->
        val x = i % 160
        val y = i / 160
        val shade = ((x + y * 2 + seed * 40) % 256)
        val grid = if (x % 16 == 0 || y % 16 == 0) 0x40 else 0
        0xFF000000.toInt() or ((shade xor grid) shl 16) or (((255 - shade) / 2) shl 8) or (seed * 50 % 256)
    }
    return FramePng.encode(pixels)
}

@Composable
internal fun PauseSheetCatalog(landscape: Boolean) = GameBackdrop {
    PauseSheet(landscape = landscape, busy = false, title = "CONTADOR", onContinue = {}, onStates = {}, onCustomize = {}, onExit = {})
}

/** N6: los ids antiguos de estados muestran ahora la hoja «Momentos» de la pausa. */
@Composable
internal fun StatesSheetCatalog(landscape: Boolean) = GameBackdrop {
    com.joelbermudez.pocketgb.debug.catalog.MomentsCatalogSheet(landscape)
}

@Composable
internal fun ExitSaveFailedCatalog(risk: Boolean) = GameBackdrop {
    ExitSaveFailedDialog(
        error = IOException("No queda espacio en el teléfono"),
        confirmingRisk = risk,
        onRetry = {},
        onKeepPlaying = {},
        onRequestLeave = {},
        onConfirmLeave = {},
        onCancelLeave = {},
    )
}

/** Juego con el indicador persistente de guardado pendiente y el aviso breve que lo acompaña. */
@Composable
internal fun SaveProblemCatalog() = GameBackdrop {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxSize()) {
        SaveProblemBanner(
            IOException("disco lleno"),
            Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp),
        )
        Snackbar(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) {
            Text(noticeText(context, GameNotice.SavePending))
        }
    }
}

/** N6: el rescate (J6) migrado a un momento «Rescate» y el aviso que se muestra al abrir el juego. */
@Composable
internal fun StatesRescueCatalog() = GameBackdrop {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxSize()) {
        com.joelbermudez.pocketgb.debug.catalog.MomentsCatalogSheet(false, com.joelbermudez.pocketgb.debug.catalog.N6Data.rescueUi())
        Snackbar(Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding().padding(16.dp)) {
            Text(noticeText(context, GameNotice.RescueMoment))
        }
    }
}

@Composable
internal fun SaveWarningCatalog() = GameBackdrop { SaveLoadWarningDialog(SaveLoadWarning.MirrorReadOnly) {} }

@Composable
internal fun OpenErrorCatalog() = GameBackdrop { OpenErrorDialog(OpenError.MirrorNotDownloaded) {} }

@Composable
internal fun SavesSettingsCatalog() {
    val games = remember {
        listOf(
            SavedGameUi(
                "9d4c1e07".repeat(8), "POKÉMON RED", "Pokemon Red.gb",
                listOf(SaveStore.BackupInfo(1, 1_759_700_000_000), SaveStore.BackupInfo(2, 1_759_600_000_000), SaveStore.BackupInfo(3, null)),
            ),
            SavedGameUi("5a2f9b31".repeat(8), "POKÉMON YELLOW", "Amarillo/Pokemon Yellow.gbc", emptyList()),
            SavedGameUi("c0ffee01".repeat(8), null, null, listOf(SaveStore.BackupInfo(1, 1_759_500_000_000))),
        )
    }
    SavesSettingsContent(games, openFingerprint = null, snackbar = remember { SnackbarHostState() }, onRestore = { _, _ -> }, onBack = {})
}
