package com.joelbermudez.pocketgb.ui.gameplay

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.game.StatesUi
import com.joelbermudez.pocketgb.saves.StateSlot
import java.text.DateFormat
import java.util.Date

@Composable
fun slotLabel(slot: StateSlot): String = when (slot) {
    StateSlot.AUTO -> stringResource(R.string.state_auto)
    StateSlot.MANUAL1 -> stringResource(R.string.state_slot, 1)
    StateSlot.MANUAL2 -> stringResource(R.string.state_slot, 2)
    StateSlot.MANUAL3 -> stringResource(R.string.state_slot, 3)
    StateSlot.MANUAL4 -> stringResource(R.string.state_slot, 4)
    StateSlot.RESCUE -> stringResource(R.string.state_rescue)
}

private sealed interface PendingAction {
    val slot: StateSlot
    data class Replace(override val slot: StateSlot) : PendingAction
    data class Load(override val slot: StateSlot) : PendingAction
    data class Delete(override val slot: StateSlot) : PendingAction
}

/** Hoja de estados: automático + 4 manuales con captura, fecha y Guardar/Cargar/Eliminar. */
@Composable
fun StatesSheet(
    landscape: Boolean,
    ui: StatesUi,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onSave: (StateSlot) -> Unit,
    onLoad: (StateSlot, Boolean) -> Unit,
    onDelete: (StateSlot) -> Unit,
) {
    SheetOrDialog(landscape = landscape, onDismiss = onBack, modifier = Modifier.testTag("states-sheet")) {
        StatesContent(ui, snackbar, onBack, onSave, onLoad, onDelete)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatesContent(
    ui: StatesUi,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onSave: (StateSlot) -> Unit,
    /** `(ranura, guardarActualEnAuto)`: K14. */
    onLoad: (StateSlot, Boolean) -> Unit,
    onDelete: (StateSlot) -> Unit,
) {
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("states-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.states_back))
            }
            Text(
                stringResource(R.string.states_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (ui.busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).width(24.dp), strokeWidth = 2.dp)
        }
        StateSlot.entries.forEach { slot ->
            val entry = ui.entries[slot]
            // La ranura de rescate solo aparece si existe (J6): no se puede guardar manualmente en ella.
            if (slot == StateSlot.RESCUE && entry == null) return@forEach
            val label = slotLabel(slot)
            val tag = slot.fileStem
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.fillMaxWidth().testTag("state-row-$tag"),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumbnail(entry?.thumbnail, label)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    entry == null -> stringResource(R.string.state_slot_empty)
                                    entry.corrupt -> stringResource(R.string.state_corrupt)
                                    else -> formatStateDate(entry.dateMs)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (slot != StateSlot.AUTO && slot != StateSlot.RESCUE) {
                            FilledTonalButton(
                                onClick = { if (entry == null) onSave(slot) else pending = PendingAction.Replace(slot) },
                                enabled = !ui.busy,
                                contentPadding = CompactPadding,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("state-save-$tag"),
                            ) { Text(stringResource(R.string.state_save)) }
                        }
                        if (entry != null && !entry.corrupt) {
                            OutlinedButton(
                                onClick = { pending = PendingAction.Load(slot) },
                                enabled = !ui.busy,
                                contentPadding = CompactPadding,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("state-load-$tag"),
                            ) { Text(stringResource(R.string.state_load)) }
                        }
                        if (entry != null) {
                            TextButton(
                                onClick = { pending = PendingAction.Delete(slot) },
                                enabled = !ui.busy,
                                contentPadding = CompactPadding,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("state-delete-$tag"),
                            ) { Text(stringResource(R.string.state_delete)) }
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.gameplay_states_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SnackbarHost(snackbar)
    }
    (pending as? PendingAction.Load)?.let { action ->
        LoadStateConfirmDialog(
            slot = action.slot,
            slotLabel = slotLabel(action.slot).lowercase(),
            onSaveAndLoad = { pending = null; onLoad(action.slot, true) },
            onLoadWithoutSaving = { pending = null; onLoad(action.slot, false) },
            onCancel = { pending = null },
        )
    }
    pending?.takeIf { it !is PendingAction.Load }?.let { action ->
        val label = slotLabel(action.slot)
        val (title, body, confirm, destructiveColor) = when (action) {
            is PendingAction.Replace -> Quad(
                stringResource(R.string.gameplay_replace_title, label.lowercase()),
                ui.entries[action.slot]?.takeIf { !it.corrupt }?.let { entry ->
                    stringResource(R.string.gameplay_replace_body, formatStateDate(entry.dateMs))
                } ?: stringResource(R.string.gameplay_replace_body_undated),
                stringResource(R.string.gameplay_replace_confirm),
                true,
            )
            is PendingAction.Load -> error("la carga tiene su propio diálogo")
            is PendingAction.Delete -> Quad(
                stringResource(R.string.state_delete_title, label),
                stringResource(R.string.state_delete_body),
                stringResource(R.string.state_delete_confirm),
                true,
            )
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        when (action) {
                            is PendingAction.Replace -> onSave(action.slot)
                            is PendingAction.Load -> Unit
                            is PendingAction.Delete -> onDelete(action.slot)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (destructiveColor) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("state-confirm"),
                ) { Text(confirm) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pending = null },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("state-cancel"),
                ) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

private fun formatStateDate(dateMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(dateMs))

private val CompactPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)

private data class Quad(val a: String, val b: String, val c: String, val d: Boolean)

@Composable
private fun Thumbnail(png: ByteArray?, label: String) {
    val bitmap = remember(png) {
        png?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val description = stringResource(R.string.state_preview, label)
    Box(
        modifier = Modifier
            .width(88.dp)
            .aspectRatio(10f / 9f)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Icon(
                Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "" },
            )
        }
    }
}
