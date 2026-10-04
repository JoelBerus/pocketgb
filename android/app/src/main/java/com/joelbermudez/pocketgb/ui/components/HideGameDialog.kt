package com.joelbermudez.pocketgb.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Confirmación de ocultar: dice qué se conserva y dónde se recupera. */
@Composable
fun HideGameDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Ocultar de PocketGB?") },
        text = {
            Text(
                "«$title» dejará de aparecer en la biblioteca. No se borra el ROM ni la partida; " +
                    "puedes mostrarlo de nuevo en Ajustes › Biblioteca.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Ocultar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
