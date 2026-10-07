package com.joelbermudez.pocketgb.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.joelbermudez.pocketgb.R

/** Confirmación de ocultar: dice qué se conserva y dónde se recupera. El botón es destructivo (en el color de error). */
@Composable
fun HideGameDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hide_title)) },
        text = { Text(stringResource(R.string.hide_message, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.hide_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hide_cancel)) } },
    )
}
