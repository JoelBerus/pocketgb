package com.joelbermudez.pocketgb.ui.moments

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.saves.MomentStore
import java.text.DateFormat
import java.util.Date

/** Dónde se muestran los momentos: en la pausa (sesión abierta) o en el detalle (sin sesión). */
enum class MomentsPlace { GAME, DETAILS }

/** Acciones de la lista de momentos. `null` = no se ofrece en ese sitio. */
class MomentActions(
    val onCreate: ((String) -> Unit)? = null,
    val onLoad: (MomentStore.Moment) -> Unit,
    /** Recuperar una entrada del anillo «Antes de cargar». */
    val onRecover: (MomentStore.Moment) -> Unit,
    /** Solo en el detalle: instalar la partida (RAM) de un momento, aunque su estado ya no cargue (ND13). */
    val onRecoverSram: ((MomentStore.Moment) -> Unit)? = null,
    val onEdit: (MomentStore.Moment, String, List<String>, String?, String) -> Unit,
    val onDelete: (MomentStore.Kind, MomentStore.Moment) -> Unit,
)

private sealed interface Pending {
    data class Create(val suggested: String) : Pending
    data class Load(val moment: MomentStore.Moment) : Pending
    data class Recover(val entry: MomentStore.Moment) : Pending
    data class RecoverSram(val moment: MomentStore.Moment) : Pending
    data class Edit(val moment: MomentStore.Moment) : Pending
    data class Delete(val kind: MomentStore.Kind, val moment: MomentStore.Moment) : Pending
}

/** Diálogo abierto de inicio: solo el catálogo de capturas. */
enum class MomentsPreview { CREATE, LOAD, RECOVER, EDIT }

/**
 * N6 · lista de momentos (§3.3): anillo «Antes de cargar» con «Recuperar» en un toque, filtro por etiqueta, momentos
 * agrupados por colección con miniatura, nombre, fecha, tiempo jugado, etiquetas y nota, y guía. Cargar, recuperar y
 * borrar piden confirmación; cargar explica que cambia la partida.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MomentsList(
    place: MomentsPlace,
    snapshot: MomentStore.Snapshot,
    thumbnails: Map<String, ByteArray>,
    busy: Boolean,
    actions: MomentActions,
    /** Configuración de la sesión abierta (en la pausa), para avisar si un momento se guardó con otra. */
    currentConfig: Map<String, String>? = null,
    preview: MomentsPreview? = null,
) {
    val suggested = stringResource(R.string.n6_moments_suggested, snapshot.moments.size + 1)
    var pending by remember {
        mutableStateOf<Pending?>(
            when (preview) {
                MomentsPreview.CREATE -> Pending.Create(suggested)
                MomentsPreview.LOAD -> snapshot.moments.firstOrNull()?.let { Pending.Load(it) }
                MomentsPreview.RECOVER -> snapshot.beforeLoad.firstOrNull()?.let { Pending.Recover(it) }
                MomentsPreview.EDIT -> snapshot.moments.firstOrNull()?.let { Pending.Edit(it) }
                null -> null
            },
        )
    }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (actions.onCreate != null) {
            FilledTonalButton(
                onClick = { pending = Pending.Create(suggested) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("moments-new"),
            ) { Text(stringResource(R.string.n6_moments_new)) }
        }
        if (snapshot.beforeLoad.isNotEmpty()) {
            RingSection(place, snapshot.beforeLoad, thumbnails, busy) { pending = Pending.Recover(it) }
        }
        val allTags = snapshot.moments.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        if (allTags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("moments-tags").semantics { },
            ) {
                FilterChip(selected = tagFilter == null, onClick = { tagFilter = null }, label = { Text(stringResource(R.string.n6_moments_all)) })
                for (tag in allTags) {
                    FilterChip(
                        selected = tagFilter.equals(tag, ignoreCase = true),
                        onClick = { tagFilter = if (tagFilter.equals(tag, ignoreCase = true)) null else tag },
                        label = { Text(tag) },
                        modifier = Modifier.testTag("moments-tag-$tag"),
                    )
                }
            }
        }
        val shown = snapshot.moments.filter { m -> tagFilter == null || m.tags.any { it.equals(tagFilter, ignoreCase = true) } }
        if (snapshot.moments.isEmpty()) {
            Text(
                stringResource(R.string.n6_moments_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("moments-empty"),
            )
        }
        // Agrupados por colección (en orden alfabético) y, al final, los que no tienen.
        val groups = shown.groupBy { it.collection }
        val ordered: List<String?> = groups.keys.filterNotNull().sortedBy { it.lowercase() } + (if (groups.containsKey(null)) listOf(null) else emptyList())
        val showHeaders = groups.keys.any { it != null }
        for (collection in ordered) {
            if (showHeaders) {
                Text(
                    collection ?: stringResource(R.string.n6_moments_no_collection),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }.testTag("moments-collection-${collection ?: "none"}"),
                )
            }
            for (moment in groups[collection].orEmpty()) {
                MomentCard(
                    moment = moment,
                    thumbnail = thumbnails["m-${moment.id}"],
                    busy = busy,
                    onLoad = { pending = Pending.Load(moment) }.takeIf { moment.hasState },
                    onRecoverSram = { pending = Pending.RecoverSram(moment) }.takeIf { actions.onRecoverSram != null && moment.hasSram },
                    onEdit = { pending = Pending.Edit(moment) },
                    onDelete = { pending = Pending.Delete(MomentStore.Kind.MOMENT, moment) },
                )
            }
        }
        Text(
            stringResource(R.string.n6_guide_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("moments-guide"),
        )
    }
    when (val p = pending) {
        null -> Unit
        is Pending.Create -> NameDialog(
            title = stringResource(R.string.n6_create_title),
            body = stringResource(R.string.n6_create_body),
            initial = p.suggested,
            confirm = stringResource(R.string.n6_create_confirm),
            onConfirm = { pending = null; actions.onCreate?.invoke(it) },
            onDismiss = { pending = null },
        )
        is Pending.Load -> {
            val mismatch = currentConfig?.let { configDifference(p.moment.config, it) }
            val body = stringResource(R.string.n6_load_body)
            val configText = mismatch?.let { stringResource(R.string.n6_load_config, it) }
            val detailsText = stringResource(R.string.n6_load_from_details).takeIf { place == MomentsPlace.DETAILS }
            ConfirmDialog(
                title = stringResource(R.string.n6_load_title, p.moment.name),
                body = listOfNotNull(body, configText, detailsText).joinToString("\n\n"),
                confirm = stringResource(R.string.n6_load_confirm),
                destructive = false,
                onConfirm = { pending = null; actions.onLoad(p.moment) },
                onDismiss = { pending = null },
            )
        }
        is Pending.Recover -> ConfirmDialog(
            title = stringResource(R.string.n6_recover_title),
            body = stringResource(
                if (place == MomentsPlace.GAME) R.string.n6_recover_body_game else R.string.n6_recover_body_details,
                p.entry.name,
            ),
            confirm = stringResource(R.string.n6_recover),
            destructive = false,
            onConfirm = { pending = null; actions.onRecover(p.entry) },
            onDismiss = { pending = null },
        )
        is Pending.RecoverSram -> ConfirmDialog(
            title = stringResource(R.string.n6_recover_sram_title, p.moment.name),
            body = stringResource(R.string.n6_recover_sram_body),
            confirm = stringResource(R.string.n6_recover),
            destructive = false,
            onConfirm = { pending = null; actions.onRecoverSram?.invoke(p.moment) },
            onDismiss = { pending = null },
        )
        is Pending.Edit -> EditDialog(
            moment = p.moment,
            collections = snapshot.moments.mapNotNull { it.collection },
            onConfirm = { name, tags, collection, note -> pending = null; actions.onEdit(p.moment, name, tags, collection, note) },
            onDismiss = { pending = null },
        )
        is Pending.Delete -> ConfirmDialog(
            title = stringResource(R.string.n6_delete_title, p.moment.name),
            body = stringResource(R.string.n6_delete_body),
            confirm = stringResource(R.string.n6_delete_confirm),
            destructive = true,
            onConfirm = { pending = null; actions.onDelete(p.kind, p.moment) },
            onDismiss = { pending = null },
        )
    }
}

/** Qué cambia entre la configuración del momento y la de la sesión (`modelo DMG → CGB`), o `null` si nada relevante. */
internal fun configDifference(moment: Map<String, String>, current: Map<String, String>): String? {
    if (moment.isEmpty() || current.isEmpty()) return null
    val diffs = (moment.keys + current.keys).sorted().mapNotNull { key ->
        val a = moment[key]
        val b = current[key]
        if (a != null && b != null && a != b) "$key $a → $b" else null
    }
    return diffs.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

@Composable
private fun RingSection(
    place: MomentsPlace,
    ring: List<MomentStore.Moment>,
    thumbnails: Map<String, ByteArray>,
    busy: Boolean,
    onRecover: (MomentStore.Moment) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("moments-ring"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.n6_ring_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            ring.forEachIndexed { index, entry ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MomentThumbnail(thumbnails["b-${entry.id}"], entry.name, Modifier.width(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.n6_ring_entry, entry.name), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatMomentDate(entry.createdMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val canRecover = if (place == MomentsPlace.GAME) entry.hasState else entry.hasSram
                    if (canRecover) {
                        val button: @Composable () -> Unit = { Text(stringResource(R.string.n6_recover)) }
                        if (index == 0) {
                            FilledTonalButton(
                                onClick = { onRecover(entry) },
                                enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("moments-recover-$index"),
                            ) { Icon(Icons.Outlined.Restore, null); button() }
                        } else {
                            TextButton(
                                onClick = { onRecover(entry) },
                                enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("moments-recover-$index"),
                            ) { button() }
                        }
                    }
                }
            }
            Text(stringResource(R.string.n6_ring_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MomentCard(
    moment: MomentStore.Moment,
    thumbnail: ByteArray?,
    busy: Boolean,
    onLoad: (() -> Unit)?,
    onRecoverSram: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.fillMaxWidth().testTag("moment-${moment.id}"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                MomentThumbnail(thumbnail, moment.name, Modifier.width(88.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(moment.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        formatMomentDate(moment.createdMs),
                        moment.playTimeMs?.takeIf { it > 0 }?.let { stringResource(R.string.n6_moment_played, formatPlayTime(it)) },
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (moment.tags.isNotEmpty()) {
                        Text(moment.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (!moment.hasState) {
                        Text(stringResource(R.string.n6_moment_no_state), style = MaterialTheme.typography.bodySmall)
                    } else if (!moment.hasSram && moment.origin != null) {
                        Text(stringResource(R.string.n6_moment_migrated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (moment.note.isNotBlank()) {
                Text(moment.note, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onLoad != null) {
                    FilledTonalButton(onClick = onLoad, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("moment-load-${moment.id}")) {
                        Text(stringResource(R.string.n6_moment_load))
                    }
                }
                if (onRecoverSram != null) {
                    OutlinedButton(onClick = onRecoverSram, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("moment-sram-${moment.id}")) {
                        Text(stringResource(R.string.n6_moment_recover_sram))
                    }
                }
                OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("moment-edit-${moment.id}")) {
                    Text(stringResource(R.string.n6_moment_edit))
                }
                TextButton(
                    onClick = onDelete,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("moment-delete-${moment.id}"),
                ) { Text(stringResource(R.string.n6_moment_delete)) }
            }
        }
    }
}

/** Miniatura con la proporción de la consola (10:9 en GB, 3:2 en GBA); sin captura, un icono. */
@Composable
fun MomentThumbnail(png: ByteArray?, label: String, modifier: Modifier = Modifier) {
    val bitmap = remember(png) { png?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val ratio = bitmap?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (10f / 9f)
    Box(
        modifier = modifier.aspectRatio(ratio).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.n6_moment_preview, label),
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, modifier = Modifier.testTag("moments-dialog-body")) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.heightIn(min = 48.dp).testTag("moments-confirm"),
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("moments-cancel")) {
                Text(stringResource(R.string.n6_cancel))
            }
        },
    )
}

@Composable
private fun NameDialog(title: String, body: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MomentStore.MAX_NAME) },
                    label = { Text(stringResource(R.string.n6_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("moments-name"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.ifBlank { initial }) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("moments-confirm"),
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("moments-cancel")) {
                Text(stringResource(R.string.n6_cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(
    moment: MomentStore.Moment,
    collections: List<String>,
    onConfirm: (String, List<String>, String?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(moment.name) }
    var tags by rememberSaveable { mutableStateOf(moment.tags.joinToString(", ")) }
    var collection by rememberSaveable { mutableStateOf(moment.collection.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(moment.note) }
    val suggestions = (listOf(stringResource(R.string.n6_collection_main), stringResource(R.string.n6_collection_experiments)) + collections)
        .distinctBy { it.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.n6_edit_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(MomentStore.MAX_NAME) },
                    label = { Text(stringResource(R.string.n6_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("moments-edit-name"),
                )
                OutlinedTextField(
                    value = tags, onValueChange = { tags = it.take(400) },
                    label = { Text(stringResource(R.string.n6_tags)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("moments-edit-tags"),
                )
                OutlinedTextField(
                    value = collection, onValueChange = { collection = it.take(MomentStore.MAX_COLLECTION) },
                    label = { Text(stringResource(R.string.n6_collection)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("moments-edit-collection"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in suggestions) {
                        FilterChip(
                            selected = collection.equals(s, ignoreCase = true),
                            onClick = { collection = if (collection.equals(s, ignoreCase = true)) "" else s },
                            label = { Text(s) },
                        )
                    }
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(MomentStore.MAX_NOTE) },
                    label = { Text(stringResource(R.string.n6_note)) }, minLines = 2, maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("moments-edit-note"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.ifBlank { moment.name }, tags.split(','), collection.ifBlank { null }, note) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("moments-confirm"),
            ) { Text(stringResource(R.string.n6_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("moments-cancel")) {
                Text(stringResource(R.string.n6_cancel))
            }
        },
    )
}

fun formatMomentDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@Composable
fun formatPlayTime(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return if (minutes >= 60) stringResource(R.string.n6_hours_minutes, minutes / 60, minutes % 60) else stringResource(R.string.n6_minutes, minutes)
}
