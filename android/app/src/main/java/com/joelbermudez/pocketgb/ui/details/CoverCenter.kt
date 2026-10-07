package com.joelbermudez.pocketgb.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.artwork.CoverAvailability
import com.joelbermudez.pocketgb.library.artwork.CoverChoice
import com.joelbermudez.pocketgb.library.artwork.CoverKind
import com.joelbermudez.pocketgb.ui.a11y.LocalLargeFont

/**
 * N5 · «Portada» del centro de ajustes del juego. [shown] es la fuente que se ve ahora; [available] las que tiene el
 * juego. Las acciones las aloja quien la crea (selectores del sistema y E/S): esta parte no hace E/S.
 */
class CoverCenterState(
    val choice: CoverChoice,
    val shown: CoverKind,
    val available: CoverAvailability,
    val hasPinned: Boolean,
    val showDialog: Boolean,
    val onShowDialog: (Boolean) -> Unit,
    val onChoose: (CoverChoice) -> Unit,
    val onImportPhotos: () -> Unit,
    val onImportFile: () -> Unit,
    val onRemoveImported: () -> Unit,
    val onUnpin: () -> Unit,
    val importing: Boolean = false,
    val importFailed: Boolean = false,
)

@Composable
internal fun coverKindText(kind: CoverKind): String = stringResource(
    when (kind) {
        CoverKind.IMPORTED -> R.string.n5_cover_kind_imported
        CoverKind.SIDECAR -> R.string.n5_cover_kind_folder
        CoverKind.CAPTURE -> R.string.n5_cover_kind_capture
        CoverKind.GENERATED -> R.string.n5_cover_kind_generated
    },
)

@Composable
internal fun coverChoiceText(choice: CoverChoice): String = stringResource(
    when (choice) {
        CoverChoice.AUTO -> R.string.n5_cover_choice_auto
        CoverChoice.IMAGE -> R.string.n5_cover_choice_image
        CoverChoice.CAPTURE -> R.string.n5_cover_choice_capture
        CoverChoice.GENERATED -> R.string.n5_cover_choice_generated
    },
)

/** Fila «Portada»: elección y lo que se ve («Automática · se ve: Imagen de la carpeta») y «Cambiar». */
@Composable
internal fun CoverRow(cover: CoverCenterState, enabled: Boolean, title: String) {
    val changeDescription = stringResource(R.string.n5_center_cover_change_description)
    val change: @Composable () -> Unit = {
        TextButton(
            onClick = { cover.onShowDialog(true) },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("game-center-cover-change").semantics { contentDescription = changeDescription },
        ) { Text(stringResource(R.string.n5_center_cover_change)) }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.n4_center_cover)) },
        supportingContent = {
            Text(
                stringResource(R.string.n5_center_cover_value, coverChoiceText(cover.choice), coverKindText(cover.shown)),
                modifier = Modifier.testTag("game-center-cover-value"),
            )
        },
        leadingContent = { Icon(Icons.Outlined.Image, contentDescription = null) },
        trailingContent = if (LocalLargeFont.current) null else change,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("game-center-cover"),
    )
    if (LocalLargeFont.current) Row(Modifier.padding(start = 48.dp)) { change() }
    if (cover.showDialog) CoverDialog(cover, title)
}

/** Elegir la fuente (Automática / Imagen / Captura / Generada) e importar o quitar imágenes. */
@Composable
private fun CoverDialog(cover: CoverCenterState, title: String) {
    AlertDialog(
        onDismissRequest = { cover.onShowDialog(false) },
        modifier = Modifier.testTag("cover-dialog"),
        title = { Text(stringResource(R.string.n5_cover_dialog_title, title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Lo que pasa al importar va arriba: se ve sin desplazar.
                if (cover.importing) {
                    Row(
                        Modifier.padding(bottom = 12.dp).testTag("cover-importing"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.n5_cover_importing), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (cover.importFailed) {
                    Text(
                        stringResource(R.string.n5_cover_import_failed),
                        modifier = Modifier.padding(bottom = 12.dp).testTag("cover-import-failed"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Column(Modifier.selectableGroup()) {
                    for (choice in CoverChoice.entries) ChoiceOption(cover, choice)
                }
                Column(
                    Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(Icons.Outlined.PhotoLibrary, R.string.n5_cover_import_photos, "cover-import-photos", !cover.importing, cover.onImportPhotos)
                    ActionButton(Icons.Outlined.Folder, R.string.n5_cover_import_file, "cover-import-file", !cover.importing, cover.onImportFile)
                    if (cover.available.imported) {
                        ActionButton(Icons.Outlined.Delete, R.string.n5_cover_remove_imported, "cover-remove-imported", !cover.importing, cover.onRemoveImported)
                    }
                    if (cover.hasPinned) {
                        ActionButton(Icons.Outlined.PushPin, R.string.n5_cover_unpin, "cover-unpin", !cover.importing, cover.onUnpin)
                    }
                }
                Text(
                    stringResource(R.string.n5_cover_dialog_tip),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { cover.onShowDialog(false) }, modifier = Modifier.heightIn(min = 48.dp).testTag("cover-dialog-done")) {
                Text(stringResource(R.string.n5_cover_done))
            }
        },
    )
}

@Composable
private fun ChoiceOption(cover: CoverCenterState, choice: CoverChoice) {
    val missing = when (choice) {
        CoverChoice.IMAGE -> !cover.available.hasImage
        CoverChoice.CAPTURE -> !cover.available.capture
        else -> false
    }
    val summary = when {
        missing -> stringResource(R.string.n5_cover_choice_missing)
        choice == CoverChoice.AUTO -> stringResource(R.string.n5_cover_choice_auto_summary)
        choice == CoverChoice.IMAGE -> stringResource(R.string.n5_cover_choice_image_summary)
        choice == CoverChoice.CAPTURE -> stringResource(R.string.n5_cover_choice_capture_summary)
        else -> stringResource(R.string.n5_cover_choice_generated_summary)
    }
    val selected = cover.choice == choice
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = !cover.importing, role = Role.RadioButton) { cover.onChoose(choice) }
            .testTag("cover-choice-${choice.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(coverChoiceText(choice), style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
    }
}
