package com.joelbermudez.pocketgb.ui.travel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.saves.SaveLineage
import com.joelbermudez.pocketgb.saves.SavePendingException
import com.joelbermudez.pocketgb.travel.SaveExporter
import com.joelbermudez.pocketgb.travel.SaveImporter
import com.joelbermudez.pocketgb.travel.TravelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lo que la app aporta a las acciones de viaje del detalle (sin tocar cada llamada a `GameDetailsScreen`). */
class TravelEnvironment(
    /** La partida cambió por fuera de una sesión: recalcula «Continuar» (A9). */
    val onSaveChanged: (fingerprint: String) -> Unit = {},
    /** N7c: abre el juego con «Continuar» (estado automático del paquete). */
    val onContinue: (RomEntry) -> Unit = {},
)

val LocalTravelEnvironment = compositionLocalOf { TravelEnvironment() }

/** Acciones del menú del detalle. Cada una `null` = no se ofrece. */
class TravelActions(
    val onSharePackage: () -> Unit,
    val onSavePackage: () -> Unit,
    val onExportSav: () -> Unit,
    val onImport: () -> Unit,
    /** Importa unos bytes ya leídos (intent «Abrir con»), con los mismos diálogos. */
    val importBytes: (ByteArray) -> Unit,
)

/**
 * N7b · un `.pgbm` recibido por «Abrir con» o «Compartir» (intent genérico): se valida por cabecera y se busca el juego
 * por su huella (`ROMF`, los 32 bytes). Un `.sav` crudo no dice de qué juego es: se pide importarlo desde el detalle.
 */
@Composable
fun IncomingPackageHost(
    bytes: ByteArray?,
    entries: List<RomEntry>,
    prefs: LibraryPreferencesData,
    onDone: () -> Unit,
) {
    if (bytes == null) return
    val context = LocalContext.current
    val service = remember(context) { TravelService(context.applicationContext) }
    val peek = remember(bytes) { service.importer.peek(bytes) }
    val res = context.resources
    when (peek) {
        SaveImporter.Peek.RawSave -> InfoDialog(res.getString(R.string.n7_open_raw_sav), onDone)
        is SaveImporter.Peek.Rejected -> InfoDialog(res.getString(rejectionText(peek.reason)), onDone)
        is SaveImporter.Peek.Package -> {
            val id = prefs.fingerprints.entries.firstOrNull { it.value == peek.romFingerprint }?.key
            val entry = entries.firstOrNull { it.id == id }
            if (entry == null) {
                InfoDialog(res.getString(R.string.n7_open_no_game), onDone)
            } else {
                val actions = rememberTravelActions(entry, peek.romFingerprint, prefs, onFinished = onDone)
                androidx.compose.runtime.LaunchedEffect(bytes) { actions?.importBytes?.invoke(bytes) }
            }
        }
    }
}

/** Mensaje o pregunta tras exportar o importar. */
private sealed interface TravelDialog {
    data class Message(val text: String) : TravelDialog
    data class Choose(val bytes: ByteArray, val raw: Boolean, val device: String?) : TravelDialog
    data class Imported(val text: String, val continueFrom: String?) : TravelDialog
}

/**
 * N7b · exportar (compartir con `ACTION_SEND` o guardar con `ACTION_CREATE_DOCUMENT`) e importar (`OpenDocument`, validado
 * por cabecera) la partida de [entry]. Pinta sus diálogos y devuelve las acciones para el menú del detalle.
 */
@Composable
fun rememberTravelActions(
    entry: RomEntry,
    fingerprint: String?,
    prefs: LibraryPreferencesData,
    /** Se llama al cerrar el último diálogo de una importación (para el intent recibido). */
    onFinished: () -> Unit = {},
): TravelActions? {
    if (fingerprint == null) return null
    val context = LocalContext.current
    val env = LocalTravelEnvironment.current
    val service = remember(context) { TravelService(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<TravelDialog?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val res = context.resources

    fun message(id: Int, vararg args: Any) { dialog = TravelDialog.Message(res.getString(id, *args)) }

    fun failure(error: Throwable) = when (error) {
        is SaveExporter.NoSaveException -> message(R.string.n7_export_no_save)
        is SavePendingException, is IllegalStateException -> message(R.string.n7_import_busy)
        else -> message(R.string.n7_failed, error.message ?: error.javaClass.simpleName)
    }

    fun show(result: SaveImporter.Result, bytes: ByteArray, raw: Boolean) {
        dialog = when (result) {
            is SaveImporter.Result.Rejected -> TravelDialog.Message(res.getString(rejectionText(result.reason)))
            SaveImporter.Result.SaveSizeMismatch -> TravelDialog.Message(res.getString(R.string.n7_import_size_mismatch))
            is SaveImporter.Result.NeedsChoice -> TravelDialog.Choose(bytes, raw, result.device())
            is SaveImporter.Result.Done -> {
                if (result.installed || result.continueFrom != null) env.onSaveChanged(fingerprint)
                TravelDialog.Imported(res.getString(doneText(result)), result.continueFrom)
            }
        }
    }

    fun runImport(bytes: ByteArray, raw: Boolean, choice: SaveImporter.Choice?) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val target = service.target(entry, fingerprint)
                    if (raw) service.importer.importRawSave(bytes, target, choice) else service.importer.importPackage(bytes, target, choice)
                }
            }
            outcome.fold({ show(it, bytes, raw) }, ::failure)
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { service.read(uri) } }
            bytes.fold({ runImport(it, raw = !com.joelbermudez.pocketgb.travel.PgbmResult.hasMagic(it), choice = null) }, ::failure)
        }
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TravelService.MIME_BINARY)) { uri ->
        val data = pendingExport
        pendingExport = null
        if (uri == null || data == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { service.writeTo(uri, data) } }
                .fold({ message(R.string.n7_export_saved) }, ::failure)
        }
    }

    fun build(raw: Boolean, then: (ByteArray) -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (raw) service.exporter.rawSave(fingerprint)
                    else service.exporter.buildPackage(service.exportInfo(entry, fingerprint, prefs))
                }
            }.fold(then, ::failure)
        }
    }

    val name = entry.alias ?: entry.title
    val actions = remember(entry, fingerprint, prefs) {
        TravelActions(
            onSharePackage = {
                build(raw = false) { data ->
                    runCatching { service.share(data, service.exporter.fileName(name, "pgbm"), res.getString(R.string.n7_share_title)) }
                        .onFailure(::failure)
                }
            },
            onSavePackage = {
                build(raw = false) { data ->
                    pendingExport = data
                    createDocument.launch(service.exporter.fileName(name, "pgbm"))
                }
            },
            onExportSav = {
                build(raw = true) { data ->
                    pendingExport = data
                    createDocument.launch(service.exporter.fileName(name, "sav"))
                }
            },
            onImport = { openDocument.launch(arrayOf("*/*")) },
            importBytes = { bytes -> runImport(bytes, raw = !com.joelbermudez.pocketgb.travel.PgbmResult.hasMagic(bytes), choice = null) },
        )
    }

    fun close() {
        dialog = null
        onFinished()
    }
    when (val d = dialog) {
        null -> Unit
        is TravelDialog.Message -> InfoDialog(d.text) { close() }
        is TravelDialog.Imported -> AlertDialog(
            onDismissRequest = { close() },
            title = { Text(stringResource(R.string.n7_import_title)) },
            text = { Text(d.text) },
            confirmButton = {
                if (d.continueFrom != null) {
                    TextButton(
                        onClick = { close(); env.onContinue(entry) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("travel-continue"),
                    ) { Text(stringResource(R.string.n7_continue_from, d.continueFrom)) }
                } else {
                    TextButton(onClick = { close() }, modifier = Modifier.heightIn(min = 48.dp).testTag("travel-ok")) {
                        Text(stringResource(R.string.warning_ok))
                    }
                }
            },
            dismissButton = if (d.continueFrom != null) {
                { TextButton(onClick = { close() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.n7_not_now)) } }
            } else {
                null
            },
            modifier = Modifier.testTag("travel-imported"),
        )
        is TravelDialog.Choose -> AlertDialog(
            onDismissRequest = { close() },
            title = { Text(stringResource(R.string.n7_choose_title)) },
            text = { Text(stringResource(R.string.n7_choose_body, d.device ?: stringResource(R.string.n7_other_device))) },
            confirmButton = {
                TextButton(
                    onClick = { dialog = null; runImport(d.bytes, d.raw, SaveImporter.Choice.USE_INCOMING) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("travel-use-incoming"),
                ) { Text(stringResource(R.string.n7_choose_incoming)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { dialog = null; runImport(d.bytes, d.raw, SaveImporter.Choice.KEEP_LOCAL) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("travel-keep-local"),
                ) { Text(stringResource(R.string.n7_choose_local)) }
            },
            modifier = Modifier.testTag("travel-choose"),
        )
    }
    return actions
}

private fun SaveImporter.Result.NeedsChoice.device() = otherDevice

@Composable
internal fun InfoDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("travel-ok")) { Text(stringResource(R.string.warning_ok)) }
        },
        modifier = Modifier.testTag("travel-message"),
    )
}

internal fun rejectionText(reason: SaveImporter.Rejection): Int = when (reason) {
    SaveImporter.Rejection.NOT_A_PACKAGE -> R.string.n7_reject_not_package
    SaveImporter.Rejection.DAMAGED -> R.string.n7_reject_damaged
    SaveImporter.Rejection.NEWER_APP -> R.string.n7_reject_newer
    SaveImporter.Rejection.INVALID_META -> R.string.n7_reject_meta
    SaveImporter.Rejection.OTHER_GAME -> R.string.n7_reject_other_game
    SaveImporter.Rejection.UNKNOWN_SIZES -> R.string.n7_reject_unknown_sizes
    SaveImporter.Rejection.WRONG_SIZE -> R.string.n7_reject_wrong_size
}

internal fun doneText(r: SaveImporter.Result.Done): Int = when (r.lineage) {
    SaveLineage.Incoming.INSTALL, SaveLineage.Incoming.ADVANCE -> R.string.n7_import_installed
    SaveLineage.Incoming.ALREADY_CURRENT -> R.string.n7_import_same
    SaveLineage.Incoming.STALE -> R.string.n7_import_stale
    SaveLineage.Incoming.DIVERGENCE -> if (r.installed) R.string.n7_import_used_incoming else R.string.n7_import_kept_local
}
