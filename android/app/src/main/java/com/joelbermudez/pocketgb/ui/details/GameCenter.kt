package com.joelbermudez.pocketgb.ui.details

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.CategoryPaths
import com.joelbermudez.pocketgb.ui.a11y.LocalLargeFont
import com.joelbermudez.pocketgb.ui.components.MovedBadge

/** «Leyendo el juego…» o «No se pudo leer el juego» sobre las filas que dependen de la huella. */
@Composable
internal fun CenterStatus(loading: Boolean, unavailable: Boolean) {
    when {
        loading -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("game-center-reading"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.n4_center_reading), style = MaterialTheme.typography.bodyMedium)
        }
        unavailable -> Text(
            stringResource(R.string.n4_center_unavailable),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("game-settings-unavailable"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CenterSectionHeader(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            text,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Categoría, etiquetas, portada y progreso (próximamente) y partida. */
@Composable
internal fun GameCenterRows(center: GameCenterState) {
    CategoryRow(center)
    TagsRow(center)
    SoonRow(Icons.Outlined.Image, stringResource(R.string.n4_center_cover), stringResource(R.string.n4_center_cover_soon), "game-center-cover")
    SoonRow(Icons.Outlined.Insights, stringResource(R.string.n4_center_progress), stringResource(R.string.n4_center_progress_soon), "game-center-progress")
    val openSaves = center.onOpenSaves
    if (openSaves != null) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.n4_center_saves)) },
            supportingContent = { Text(stringResource(R.string.n4_center_saves_summary)) },
            leadingContent = { Icon(Icons.Outlined.Save, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(role = Role.Button, onClick = openSaves).testTag("game-center-saves"),
        )
    }
}

/** «Categoría»: dónde se ve el juego (su carpeta o la virtual con su insignia), «Cambiar» y «Volver a su carpeta». */
@Composable
private fun CategoryRow(center: GameCenterState) {
    val root = stringResource(R.string.n4_center_category_root)
    val where = if (center.categoryPath.isEmpty()) root else CategoryPaths.display(center.categoryPath)
    val folder = if (center.folderPath.isEmpty()) root else CategoryPaths.display(center.folderPath)
    val changeDescription = stringResource(R.string.n4_center_category_change_description)
    ListItem(
        headlineContent = { Text(stringResource(R.string.n4_center_category)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (center.moved) stringResource(R.string.n4_center_category_moved, where) else stringResource(R.string.n4_center_category_folder, where),
                    modifier = Modifier.testTag("game-center-category-value"),
                )
                if (center.moved) {
                    MovedBadge()
                    Text(
                        stringResource(R.string.n4_center_category_own, folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        trailingContent = if (LocalLargeFont.current) {
            null
        } else {
            {
                TextButton(
                    onClick = center.onChangeCategory,
                    enabled = center.enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("game-center-category-change").semantics { contentDescription = changeDescription },
                ) { Text(stringResource(R.string.n4_center_category_change)) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("game-center-category"),
    )
    Row(Modifier.padding(start = 56.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (LocalLargeFont.current) {
            // Fuente grande: los botones bajo el texto, sin estrecharlo.
            TextButton(
                onClick = center.onChangeCategory,
                enabled = center.enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("game-center-category-change").semantics { contentDescription = changeDescription },
            ) { Text(stringResource(R.string.n4_center_category_change)) }
        }
        if (center.moved) {
            TextButton(
                onClick = center.onReturnToFolder,
                enabled = center.enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("game-center-category-return"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.n4_center_category_return), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** «Etiquetas»: las del juego (o «Sin etiquetas») y «Editar». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsRow(center: GameCenterState) {
    val editDescription = stringResource(R.string.n4_center_tags_edit_description)
    val edit: @Composable () -> Unit = {
        TextButton(
            onClick = center.onEditTags,
            enabled = center.enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("game-center-tags-edit").semantics { contentDescription = editDescription },
        ) { Text(stringResource(R.string.n4_center_tags_edit)) }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.n4_center_tags)) },
        supportingContent = {
            if (center.tags.isEmpty()) {
                Text(stringResource(R.string.n4_center_tags_none))
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp).testTag("game-center-tags-value"),
                ) { center.tags.forEach { TagLabel(it) } }
            }
        },
        leadingContent = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
        trailingContent = if (LocalLargeFont.current) null else edit,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("game-center-tags"),
    )
    if (LocalLargeFont.current) Row(Modifier.padding(start = 56.dp)) { edit() }
}

/** Una etiqueta en pastilla con contorno fino (detalle, centro de ajustes). */
@Composable
internal fun TagLabel(tag: String, modifier: Modifier = Modifier) {
    Text(
        tag,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Fila deshabilitada de algo que llega en otro hito (portada N5, progreso N6). */
@Composable
private fun SoonRow(icon: ImageVector, title: String, summary: String, tag: String) {
    val state = stringResource(R.string.n4_center_soon_state)
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = disabledColor,
            supportingColor = disabledColor,
            leadingIconColor = disabledColor,
        ),
        modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) {
            disabled()
            stateDescription = state
        },
    )
}

/** «Ocultar de la biblioteca» (con confirmación): nunca borra el ROM ni su partida. */
@Composable
internal fun HideRow(onHide: () -> Unit) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    ListItem(
        headlineContent = { Text(stringResource(R.string.n4_center_hide)) },
        supportingContent = { Text(stringResource(R.string.n4_center_hide_summary)) },
        leadingContent = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.error,
            leadingIconColor = MaterialTheme.colorScheme.error,
        ),
        modifier = Modifier.clickable(role = Role.Button, onClick = onHide).testTag("game-center-hide"),
    )
}
