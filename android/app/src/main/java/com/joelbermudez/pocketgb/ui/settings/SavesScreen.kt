package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.game.GameplayViewModel
import com.joelbermudez.pocketgb.saves.SavedGameUi
import com.joelbermudez.pocketgb.saves.SavesBrowser
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.ui.gameplay.causeText
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ajustes › Partidas con datos reales: lee `saves/` y restaura copias (J4). */
@Composable
fun SavesScreen(
    browser: SavesBrowser,
    gameplay: GameplayViewModel,
    onBack: () -> Unit,
    /** N4: solo la partida de esta huella (desde el centro de ajustes del juego). */
    onlyFingerprint: String? = null,
) {
    val openFingerprint by gameplay.openFingerprint.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val games by produceState<List<SavedGameUi>?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { browser.list() }.getOrDefault(emptyList()).filter { onlyFingerprint == null || it.fingerprint == onlyFingerprint }
        }
    }
    // Fecha de la partida actual: la `.sav` local de cada huella (lectura barata de la fecha, fuera del hilo principal).
    val currentDates by produceState<Map<String, Long>>(emptyMap(), games) {
        val list = games ?: return@produceState
        value = withContext(Dispatchers.IO) {
            list.mapNotNull { game ->
                File(File(context.filesDir, "saves"), "${game.fingerprint}.sav").takeIf { it.isFile }
                    ?.lastModified()?.takeIf { it > 0 }?.let { game.fingerprint to it }
            }.toMap()
        }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    SavesSettingsContent(
        games = games,
        openFingerprint = openFingerprint,
        snackbar = snackbar,
        onRestore = { fingerprint, backup ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { browser.restore(fingerprint, backup, gameplay.openFingerprint.value) }
                }
                // A9: la partida restaurada es más nueva que el estado automático: «Continuar» se recalcula.
                if (result.isSuccess) gameplay.didRestoreSave(fingerprint)
                refresh++
                snackbar.showSnackbar(
                    result.exceptionOrNull()?.let { resources.getString(R.string.saves_restore_failed, causeText(it)) }
                        ?: resources.getString(R.string.saves_restored),
                )
            }
        },
        onBack = onBack,
        currentDates = currentDates,
        onRestoreSetAside = { fingerprint, name ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { browser.restoreSetAside(fingerprint, name, gameplay.openFingerprint.value) }
                }
                if (result.isSuccess) gameplay.didRestoreSave(fingerprint)
                refresh++
                snackbar.showSnackbar(
                    result.exceptionOrNull()?.let { resources.getString(R.string.saves_restore_failed, causeText(it)) }
                        ?: resources.getString(R.string.saves_restored),
                )
            }
        },
    )
}

/** Contenido sin ViewModel: reutilizable por el catálogo y las pruebas. [games] `null` = cargando. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavesSettingsContent(
    games: List<SavedGameUi>?,
    openFingerprint: String?,
    snackbar: SnackbarHostState,
    onRestore: (fingerprint: String, backup: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Fecha (ms) de la partida actual por huella; ausente = no se conoce. */
    currentDates: Map<String, Long> = emptyMap(),
    /** N1: restaurar una partida apartada (huella, nombre del archivo). */
    onRestoreSetAside: (fingerprint: String, name: String) -> Unit = { _, _ -> },
) {
    var pending by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var pendingSetAside by remember { mutableStateOf<Pair<String, String>?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.saves_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.saves_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            games == null -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            games.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Save,
                title = stringResource(R.string.saves_empty_title),
                message = stringResource(R.string.saves_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("saves-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(games, key = { it.fingerprint }) { game ->
                    val open = game.fingerprint == openFingerprint
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth().testTag("saved-game-${game.fingerprint.take(8)}"),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                game.title ?: stringResource(R.string.saves_unknown_game, game.fingerprint.take(8)),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            game.fileName?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            currentDates[game.fingerprint]?.let { date ->
                                Text(
                                    stringResource(R.string.saves_current_saved, formatDate(date)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("saved-current-${game.fingerprint.take(8)}"),
                                )
                            }
                            Text(stringResource(R.string.saves_backups), style = MaterialTheme.typography.labelLarge)
                            if (game.backups.isEmpty()) {
                                Text(stringResource(R.string.saves_no_backups), style = MaterialTheme.typography.bodyMedium)
                            }
                            game.backups.forEach { backup ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    val dateText = backup.dateMs?.let(::formatDate)
                                        ?: stringResource(R.string.saves_backup_unknown_date)
                                    Text(
                                        if (backup.index == 1) {
                                            stringResource(R.string.saves_backup_latest, dateText)
                                        } else {
                                            stringResource(R.string.saves_backup_item, backup.index, dateText)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilledTonalButton(
                                        onClick = { pending = game.fingerprint to backup.index },
                                        enabled = !open,
                                        modifier = Modifier.heightIn(min = 48.dp)
                                            .testTag("restore-${game.fingerprint.take(8)}-${backup.index}"),
                                    ) { Text(stringResource(R.string.saves_restore)) }
                                }
                            }
                            if (game.setAside.isNotEmpty()) {
                                Text(stringResource(R.string.n1_saves_set_aside), style = MaterialTheme.typography.labelLarge)
                                game.setAside.forEach { item ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    ) {
                                        val dateText = item.dateMs?.let(::formatDate)
                                            ?: stringResource(R.string.saves_backup_unknown_date)
                                        Text(
                                            stringResource(R.string.n1_saves_set_aside_item, dateText),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        FilledTonalButton(
                                            onClick = { pendingSetAside = game.fingerprint to item.name },
                                            enabled = !open,
                                            modifier = Modifier.heightIn(min = 48.dp)
                                                .testTag("restore-set-aside-${game.fingerprint.take(8)}-${item.name.takeLast(12)}"),
                                        ) { Text(stringResource(R.string.saves_restore)) }
                                    }
                                }
                                Text(
                                    stringResource(R.string.n1_saves_set_aside_footer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (game.providerConflicts.isNotEmpty()) {
                                Text(stringResource(R.string.n7_saves_provider_conflicts), style = MaterialTheme.typography.labelLarge)
                                game.providerConflicts.forEach { c ->
                                    Text(
                                        stringResource(
                                            R.string.n7_saves_provider_conflict_item, c.name,
                                            c.dateMs?.let(::formatDate) ?: stringResource(R.string.saves_backup_unknown_date),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.testTag("provider-conflict-${game.fingerprint.take(8)}"),
                                    )
                                }
                                Text(
                                    stringResource(R.string.n7_saves_provider_conflicts_footer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (game.backups.isNotEmpty() || game.setAside.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.saves_restore_footer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (open) {
                                Text(
                                    stringResource(R.string.saves_restore_disabled_open),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "saves-footer") {
                    Text(
                        stringResource(R.string.saves_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    pendingSetAside?.let { (fingerprint, name) ->
        SetAsideConfirm(
            onConfirm = {
                pendingSetAside = null
                onRestoreSetAside(fingerprint, name)
            },
            onDismiss = { pendingSetAside = null },
        )
    }
    pending?.let { (fingerprint, backup) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.saves_restore_title, backup)) },
            text = { Text(stringResource(R.string.saves_restore_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        onRestore(fingerprint, backup)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("restore-confirm"),
                ) { Text(stringResource(R.string.saves_restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun SetAsideConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.n1_saves_restore_set_aside_title)) },
        text = { Text(stringResource(R.string.saves_restore_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("restore-confirm")) {
                Text(stringResource(R.string.saves_restore_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

private fun formatDate(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
