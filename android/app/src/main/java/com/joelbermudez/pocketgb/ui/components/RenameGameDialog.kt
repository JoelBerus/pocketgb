package com.joelbermudez.pocketgb.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.Alias
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.RomEntry

/**
 * Renombrar (A9, iOS `RenameGameView`): alias visual local de [entry]. Arranca con el alias actual (vacío si no hay),
 * limita a [Alias.MAX_LENGTH] caracteres visibles y, vacío, vuelve al título de la cabecera. Nunca cambia el ROM, su
 * `.sav` ni sus estados.
 */
@Composable
fun RenameGameDialog(
    entry: RomEntry,
    currentAlias: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Solo el catálogo de capturas: no pide el foco (sin teclado en la captura). */
    requestFocus: Boolean = true,
) {
    var value by rememberSaveable(entry.id, stateSaver = TextFieldValue.Saver) {
        val initial = currentAlias.orEmpty()
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val save = { onSave(value.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.a9_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { next ->
                        // Tope de 80 caracteres visibles al escribir o pegar (como iOS): nunca parte un emoji ni un acento.
                        value = if (Alias.length(next.text) > Alias.MAX_LENGTH) {
                            val cut = Alias.truncate(next.text, Alias.MAX_LENGTH)
                            TextFieldValue(cut, selection = TextRange(cut.length))
                        } else {
                            next
                        }
                    },
                    label = { Text(stringResource(R.string.a9_rename_label)) },
                    placeholder = { Text(entry.title) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.a9_rename_counter, Alias.length(value.text), Alias.MAX_LENGTH))
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("rename-field"),
                )
                Text(
                    stringResource(R.string.a9_rename_footer, entry.title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("rename-footer"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = save, modifier = Modifier.heightIn(min = 48.dp).testTag("rename-save")) {
                Text(stringResource(R.string.a9_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("rename-cancel")) {
                Text(stringResource(R.string.a9_rename_cancel))
            }
        },
        modifier = Modifier.testTag("rename-dialog"),
    )
}

/**
 * Aloja [RenameGameDialog] para [entry] (o nada si es `null`) con las preferencias de la biblioteca: guarda con
 * [onSetAlias] y se cierra. Lo comparten la tarjeta (menú contextual), el detalle y los ajustes del juego.
 */
@Composable
fun RenameGameHost(
    entry: RomEntry?,
    prefs: LibraryPreferencesData,
    onSetAlias: (RomEntry, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (entry == null) return
    RenameGameDialog(
        entry = entry,
        currentAlias = prefs.aliasOf(entry),
        onSave = { name ->
            onSetAlias(entry, name)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
