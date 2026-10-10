package com.joelbermudez.pocketgb.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.CategoryPaths
import com.joelbermudez.pocketgb.library.Tags
import com.joelbermudez.pocketgb.ui.a11y.LocalLargeFont

/** Superficie común de los diálogos del centro de ajustes: título, contenido desplazable y botones abajo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CenterDialog(
    title: String,
    tag: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .testTag(tag)
                .semantics { paneTitle = title },
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
                )
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                ) { content() }
                // En varias filas si no caben (fuente grande o «Volver a su carpeta» + «Cancelar» + «Mostrar aquí»).
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) { buttons() }
            }
        }
    }
}

/**
 * N4 · «Mostrar en categoría…» (ND3): elige una categoría que ya existe (carpetas con juegos, sus carpetas padre y otras
 * categorías virtuales) o escribe una nueva («Pokémon/Favoritas»). El juego se verá ahí en vez de en la de su carpeta;
 * PocketGB solo lo recuerda (nunca mueve el archivo). Con el juego ya movido ofrece «Volver a su carpeta».
 */
@Composable
fun CategoryPickerDialog(
    current: List<String>,
    folder: List<String>,
    moved: Boolean,
    known: List<List<String>>,
    onPick: (List<String>) -> Unit,
    onReturnToFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Se guarda como texto (sobrevive a girar): niveles unidos por «/» (ningún nombre lo lleva), «» = la raíz.
    var selectedKey by rememberSaveable { mutableStateOf(current.joinToString("/")) }
    val selected = if (selectedKey.isEmpty()) emptyList() else selectedKey.split('/')
    var typed by rememberSaveable { mutableStateOf("") }
    val parsed = remember(typed) { if (typed.isBlank()) null else CategoryPaths.parse(typed) }
    val choice: List<String>? = when (parsed) {
        null -> selected
        // H13: si coincide sin mayúsculas ni acentos con una que ya existe, se usa esa.
        is CategoryPaths.Parsed.Valid -> CategoryPaths.matchExisting(parsed.path, known)
        is CategoryPaths.Parsed.Invalid -> null
    }
    val root = stringResource(R.string.n4_center_category_root)
    CenterDialog(
        title = stringResource(R.string.n4_picker_title),
        tag = "category-picker",
        onDismiss = onDismiss,
        buttons = {
            if (moved) {
                TextButton(onClick = onReturnToFolder, modifier = Modifier.heightIn(min = 48.dp).testTag("category-picker-return")) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.n4_center_category_return), modifier = Modifier.padding(start = 6.dp))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("category-picker-cancel")) {
                Text(stringResource(R.string.n4_cancel))
            }
            Button(
                onClick = { choice?.let(onPick) },
                enabled = choice != null && choice != current,
                modifier = Modifier.heightIn(min = 48.dp).testTag("category-picker-apply"),
            ) { Text(stringResource(R.string.n4_picker_apply)) }
        },
    ) {
        Text(
            stringResource(R.string.n4_picker_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            stringResource(R.string.n4_picker_list),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).semantics { heading() },
        )
        val options = listOf(emptyList<String>()) + known
        options.forEach { path ->
            val label = if (path.isEmpty()) root else path.last()
            val notes = listOfNotNull(
                stringResource(R.string.n4_picker_own_folder).takeIf { path == folder },
                stringResource(R.string.n4_picker_current).takeIf { path == current },
            )
            val isSelected = typed.isBlank() && selected == path
            val full = if (path.isEmpty()) root else CategoryPaths.display(path)
            val description = (listOf(full) + notes).joinToString(", ")
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton) {
                        selectedKey = path.joinToString("/")
                        typed = ""
                    }
                    .semantics(mergeDescendants = true) { contentDescription = description }
                    .padding(start = (12 + 16 * (path.size - 1).coerceAtLeast(0)).dp, end = 24.dp)
                    .testTag("category-option-${if (path.isEmpty()) "." else path.joinToString("/")}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Icon(
                    if (path.isEmpty()) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (LocalLargeFont.current) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (notes.isNotEmpty()) {
                        Text(
                            notes.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        // La nueva categoría va debajo de la lista: elegir una que ya existe es lo habitual.
        val supporting = when ((parsed as? CategoryPaths.Parsed.Invalid)?.problem) {
            CategoryPaths.Problem.RESERVED -> stringResource(R.string.n4_picker_error_reserved)
            CategoryPaths.Problem.TOO_DEEP -> stringResource(R.string.n4_picker_error_deep)
            else -> if (parsed is CategoryPaths.Parsed.Valid && choice != null && choice != parsed.path) {
                stringResource(R.string.n4_picker_reuses, CategoryPaths.display(choice))
            } else {
                stringResource(R.string.n4_picker_new_hint)
            }
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it.take(CategoryPaths.MAX_SEGMENT_LENGTH * 6) },
            label = { Text(stringResource(R.string.n4_picker_new)) },
            supportingText = { Text(supporting) },
            isError = parsed is CategoryPaths.Parsed.Invalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { choice?.takeIf { it != current }?.let(onPick) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp).testTag("category-picker-field"),
        )
    }
}

/**
 * N4 · editor de etiquetas: las del juego (con «quitar»), un campo para añadir otra y las que ya usas en otros juegos.
 * Los cambios se guardan al momento (por huella).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorDialog(
    title: String,
    tags: List<String>,
    suggestions: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val full = tags.size >= Tags.MAX_PER_GAME
    val add = {
        Tags.normalize(typed)?.let(onAdd)
        typed = ""
    }
    CenterDialog(
        title = stringResource(R.string.n4_tags_title, title),
        tag = "tag-editor",
        onDismiss = onDismiss,
        buttons = {
            Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("tag-editor-done")) {
                Text(stringResource(R.string.n4_done))
            }
        },
    ) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tags.isEmpty()) {
                Text(stringResource(R.string.n4_tags_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("tag-editor-current")) {
                    tags.forEach { tag ->
                        val removeDescription = stringResource(R.string.n4_tags_remove, tag)
                        InputChip(
                            selected = false,
                            onClick = { onRemove(tag) },
                            label = { Text(tag) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("tag-chip-$tag")
                                .semantics { contentDescription = removeDescription },
                        )
                    }
                }
            }
            val addDescription = stringResource(R.string.n4_tags_add)
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(Tags.MAX_LENGTH * 2) },
                label = { Text(stringResource(R.string.n4_tags_field)) },
                singleLine = true,
                enabled = !full,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                trailingIcon = {
                    IconButton(
                        onClick = add,
                        enabled = !full && Tags.normalize(typed) != null,
                        modifier = Modifier.testTag("tag-editor-add"),
                    ) { Icon(Icons.Filled.Add, contentDescription = addDescription) }
                },
                modifier = Modifier.fillMaxWidth().testTag("tag-editor-field"),
            )
            if (full) {
                Text(stringResource(R.string.n4_tags_full), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val others = suggestions.filter { suggestion -> tags.none { Tags.same(it, suggestion) } }
            if (others.isNotEmpty() && !full) {
                Text(
                    stringResource(R.string.n4_tags_suggestions),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { suggestion ->
                        val addDescription = stringResource(R.string.n4_tags_add_suggestion, suggestion)
                        SuggestionChip(
                            onClick = { onAdd(suggestion) },
                            label = { Text(suggestion) },
                            icon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("tag-suggestion-$suggestion")
                                .semantics { contentDescription = addDescription },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.n4_tags_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
