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
    /** ND20 (i): fusiona los metadatos del paquete importado con los de aquí (se llama fuera del hilo principal). */
    val onMergeMetadata: (RomEntry, String, com.joelbermudez.pocketgb.travel.PgbmMeta) -> Unit = { _, _, _ -> },
)

val LocalTravelEnvironment = compositionLocalOf { TravelEnvironment() }

/** Acciones del menú del detalle. Cada una `null` = no se ofrece. */
class TravelActions(
    /** N7c: el `.pgbm` va directo a `PocketGB/Intercambio/` de la carpeta (Drive). */
    val onSend: () -> Unit,
    val onSharePackage: () -> Unit,
    val onSavePackage: () -> Unit,
    val onExportSav: () -> Unit,
    val onImport: () -> Unit,
    /** Importa unos bytes ya leídos (intent «Abrir con»), con los mismos diálogos. */
    val importBytes: (ByteArray) -> Unit,
)

/**
 * N7b · un archivo recibido por «Abrir con» o «Compartir» (intent genérico): se valida por cabecera (H11: fuera del hilo
 * principal). Un `.pgbm` busca su juego por la huella (`ROMF`, los 32 bytes); un `.sav` crudo, por el nombre del archivo
 * sin el sufijo de copia en conflicto (ND20 e, l): si coincide con varios juegos, se elige. [onImported] se llama solo si
 * la importación terminó (H6).
 */
@Composable
fun IncomingPackageHost(
    bytes: ByteArray?,
    entries: List<RomEntry>,
    prefs: LibraryPreferencesData,
    onDone: () -> Unit,
    displayName: String? = null,
    onImported: () -> Unit = {},
) {
    if (bytes == null) return
    val context = LocalContext.current
    val service = remember(context) { TravelService(context.applicationContext) }
    val peek by androidx.compose.runtime.produceState<SaveImporter.Peek?>(null, bytes) {
        value = withContext(Dispatchers.IO) { service.importer.peek(bytes) }
    }
    val res = androidx.compose.ui.platform.LocalResources.current
    when (val p = peek) {
        null -> Unit
        SaveImporter.Peek.RawSave -> {
            val stem = displayName?.substringBeforeLast('.')?.let(com.joelbermudez.pocketgb.saves.SaveLineage::stripConflictSuffix)
            val matches = if (stem == null) emptyList() else entries.filter {
                it.isPlayable && prefs.fingerprints[it.id] != null && it.fileName.substringBeforeLast('.').equals(stem, ignoreCase = true)
            }.distinctBy { prefs.fingerprints[it.id] }
            var chosen by remember(bytes) { mutableStateOf(matches.singleOrNull()) }
            val entry = chosen
            when {
                matches.isEmpty() -> InfoDialog(res.getString(R.string.n7_open_raw_sav), onDone)
                entry == null -> ChooseGameDialog(matches, onChoose = { chosen = it }, onDismiss = onDone)
                else -> {
                    val actions = rememberTravelActions(entry, prefs.fingerprints[entry.id], prefs, onFinished = onDone, onImported = onImported)
                    androidx.compose.runtime.LaunchedEffect(bytes, entry.id) { actions?.importBytes?.invoke(bytes) }
                }
            }
        }
        is SaveImporter.Peek.Rejected -> InfoDialog(res.getString(rejectionText(p.reason)), onDone)
        is SaveImporter.Peek.Package -> {
            val id = prefs.fingerprints.entries.firstOrNull { it.value == p.romFingerprint }?.key
            val entry = entries.firstOrNull { it.id == id }
            if (entry == null) {
                InfoDialog(res.getString(R.string.n7_open_no_game), onDone)
            } else {
                val actions = rememberTravelActions(entry, p.romFingerprint, prefs, onFinished = onDone, onImported = onImported)
                androidx.compose.runtime.LaunchedEffect(bytes) { actions?.importBytes?.invoke(bytes) }
            }
        }
    }
}

/** ND20 (e): un `.sav` cuyo nombre coincide con varios juegos: se elige. */
@Composable
internal fun ChooseGameDialog(games: List<RomEntry>, onChoose: (RomEntry) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.n7_open_choose_game)) },
        text = {
            androidx.compose.foundation.layout.Column {
                games.forEach { g ->
                    TextButton(onClick = { onChoose(g) }, modifier = Modifier.heightIn(min = 48.dp).testTag("choose-game-${g.id}")) {
                        Text("${g.alias ?: g.title} · ${g.id}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dialog_cancel)) }
        },
        modifier = Modifier.testTag("choose-game-dialog"),
    )
}

/** Mensaje o pregunta tras exportar o importar. */
private sealed interface TravelDialog {
    data class Message(val text: String) : TravelDialog
    data class Ask(
        val bytes: ByteArray,
        val raw: Boolean,
        val ask: SaveImporter.Ask,
        val device: String?,
        val choice: SaveImporter.Choice?,
        val confirmed: Set<SaveImporter.Ask>,
    ) : TravelDialog
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
    /** H6: la importación terminó de verdad ([SaveImporter.Result.Done]). */
    onImported: () -> Unit = {},
): TravelActions? {
    if (fingerprint == null) return null
    val context = LocalContext.current
    val env = LocalTravelEnvironment.current
    val service = remember(context) { TravelService(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<TravelDialog?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val res = androidx.compose.ui.platform.LocalResources.current

    fun message(id: Int, vararg args: Any) { dialog = TravelDialog.Message(res.getString(id, *args)) }

    fun failure(error: Throwable) = when (error) {
        is SaveExporter.NoSaveException -> message(R.string.n7_export_no_save)
        is SavePendingException, is IllegalStateException -> message(R.string.n7_import_busy)
        else -> message(R.string.n7_failed, error.message ?: error.javaClass.simpleName)
    }

    fun runImport(
        bytes: ByteArray,
        raw: Boolean,
        choice: SaveImporter.Choice? = null,
        confirmed: Set<SaveImporter.Ask> = emptySet(),
    ) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val target = service.target(entry, fingerprint)
                    val r = if (raw) service.importer.importRawSave(bytes, target, choice, confirmed)
                    else service.importer.importPackage(bytes, target, choice, confirmed)
                    // ND20 (i): los metadatos del paquete se fusionan con los de aquí.
                    if (r is SaveImporter.Result.Done && r.meta != null) runCatching { env.onMergeMetadata(entry, fingerprint, r.meta) }
                    r
                }
            }
            outcome.fold({ result ->
                dialog = when (result) {
                    is SaveImporter.Result.Rejected -> TravelDialog.Message(res.getString(rejectionText(result.reason)))
                    SaveImporter.Result.SaveSizeMismatch -> TravelDialog.Message(res.getString(R.string.n7_import_size_mismatch))
                    is SaveImporter.Result.NeedsChoice -> TravelDialog.Ask(bytes, raw, result.ask, result.otherDevice, choice, confirmed)
                    is SaveImporter.Result.Done -> {
                        if (result.installed || result.continueFrom != null) env.onSaveChanged(fingerprint)
                        onImported()
                        TravelDialog.Imported(res.getString(doneText(result)), result.continueFrom)
                    }
                }
            }, ::failure)
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { service.read(uri) } }
            bytes.fold({ runImport(it, raw = !com.joelbermudez.pocketgb.travel.PgbmResult.hasMagic(it)) }, ::failure)
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
            onSend = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val exchange = service.exchange() ?: return@runCatching null
                            val data = service.exporter.buildPackage(service.exportInfo(entry, fingerprint, prefs))
                            val id = exchange.send(
                                com.joelbermudez.pocketgb.travel.ExchangeFolder.fileName(name, service.deviceName(), System.currentTimeMillis()), data,
                            )
                            service.inbox.markSent(id)
                            id
                        }
                    }.fold(
                        { id ->
                            if (id == null) message(R.string.n7_send_no_folder)
                            else message(R.string.n7_sent, "PocketGB/" + com.joelbermudez.pocketgb.travel.ExchangeFolder.INBOX)
                        },
                        ::failure,
                    )
                }
            },
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
            importBytes = { bytes -> runImport(bytes, raw = !com.joelbermudez.pocketgb.travel.PgbmResult.hasMagic(bytes)) },
        )
    }

    fun close() {
        dialog = null
        onFinished()
    }
    when (val d = dialog) {
        null -> Unit
        is TravelDialog.Message -> InfoDialog(d.text) { close() }
        is TravelDialog.Imported -> ImportedDialog(d.text, d.continueFrom, onContinue = { close(); env.onContinue(entry) }, onDismiss = { close() })
        is TravelDialog.Ask -> when (d.ask) {
            SaveImporter.Ask.DIVERGENCE, SaveImporter.Ask.KNOWN -> ChooseDialog(
                d.device,
                known = d.ask == SaveImporter.Ask.KNOWN,
                onIncoming = { dialog = null; runImport(d.bytes, d.raw, SaveImporter.Choice.USE_INCOMING, d.confirmed) },
                onLocal = { dialog = null; runImport(d.bytes, d.raw, SaveImporter.Choice.KEEP_LOCAL, d.confirmed) },
                onDismiss = { close() },
            )
            else -> ConfirmImportDialog(
                d.ask, d.device ?: res.getString(R.string.n7_other_device), entry.alias ?: entry.title,
                onConfirm = { dialog = null; runImport(d.bytes, d.raw, d.choice, d.confirmed + d.ask) },
                onDismiss = { close() },
            )
        }
    }
    return actions
}


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
    SaveImporter.Rejection.LOCAL_UNREADABLE -> R.string.n7_reject_local_unreadable
}

internal fun doneText(r: SaveImporter.Result.Done): Int = when (r.lineage) {
    SaveLineage.Incoming.INSTALL, SaveLineage.Incoming.ADVANCE -> R.string.n7_import_installed
    SaveLineage.Incoming.ALREADY_CURRENT -> R.string.n7_import_same
    SaveLineage.Incoming.STALE -> R.string.n7_import_stale
    SaveLineage.Incoming.DIVERGENCE -> if (r.installed) R.string.n7_import_used_incoming else R.string.n7_import_kept_local
}

/** Resultado de una importación; con [continueFrom] ofrece «Continuar donde lo dejaste en <equipo>» (ND6). */
@Composable
internal fun ImportedDialog(text: String, continueFrom: String?, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.n7_import_title)) },
        text = { Text(text) },
        confirmButton = {
            if (continueFrom != null) {
                TextButton(onClick = onContinue, modifier = Modifier.heightIn(min = 48.dp).testTag("travel-continue")) {
                    Text(stringResource(R.string.n7_continue_from, continueFrom))
                }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("travel-ok")) {
                    Text(stringResource(R.string.warning_ok))
                }
            }
        },
        dismissButton = if (continueFrom != null) {
            { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.n7_not_now)) } }
        } else {
            null
        },
        modifier = Modifier.testTag("travel-imported"),
    )
}

/** Divergencia al importar: la que no se elige queda como momento «Conflicto» y en las copias. */
@Composable
internal fun ChooseDialog(device: String?, onIncoming: () -> Unit, onLocal: () -> Unit, onDismiss: () -> Unit, known: Boolean = false) {
    val who = device ?: stringResource(R.string.n7_other_device)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (known) R.string.n7_known_title else R.string.n7_choose_title)) },
        text = { Text(if (known) stringResource(R.string.n7_known_body, who) else stringResource(R.string.n7_choose_body, who)) },
        confirmButton = {
            TextButton(onClick = onIncoming, modifier = Modifier.heightIn(min = 48.dp).testTag("travel-use-incoming")) {
                Text(stringResource(R.string.n7_choose_incoming))
            }
        },
        dismissButton = {
            TextButton(onClick = onLocal, modifier = Modifier.heightIn(min = 48.dp).testTag("travel-keep-local")) {
                Text(stringResource(R.string.n7_choose_local))
            }
        },
        modifier = Modifier.testTag("travel-choose"),
    )
}

/** N7c: un paquete nuevo en `PocketGB/Intercambio/`. */
@Composable
internal fun InboxDialog(device: String, game: String, onImport: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.n7_inbox_title)) },
        text = { Text(stringResource(R.string.n7_inbox_body, device, game)) },
        confirmButton = {
            TextButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp).testTag("inbox-import")) { Text(stringResource(R.string.n7_inbox_import)) }
        },
        dismissButton = {
            TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp).testTag("inbox-later")) { Text(stringResource(R.string.n7_not_now)) }
        },
        modifier = Modifier.testTag("inbox-dialog"),
    )
}

/** ND20 (e, f, h): confirmaciones antes de importar (sin tocar nada hasta «Importar»). */
@Composable
internal fun ConfirmImportDialog(ask: SaveImporter.Ask, device: String, game: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body, confirm) = when (ask) {
        SaveImporter.Ask.RAW_CONFIRM -> Triple(R.string.n7_raw_confirm_title, stringResource(R.string.n7_raw_confirm_body, game), R.string.n7_raw_import)
        SaveImporter.Ask.REPLACE_STATE -> Triple(R.string.n7_replace_state_title, stringResource(R.string.n7_replace_state_body, device), R.string.n7_replace_state_confirm)
        else -> Triple(R.string.n7_config_mismatch_title, stringResource(R.string.n7_config_mismatch_body, device), R.string.n7_config_mismatch_confirm)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("travel-confirm")) { Text(stringResource(confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("travel-cancel")) { Text(stringResource(R.string.dialog_cancel)) }
        },
        modifier = Modifier.testTag("travel-confirm-dialog"),
    )
}
