package com.joelbermudez.pocketgb.ui.gameplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.saves.StateSlot

/**
 * Confirmación de carga (K14): «Guardar actual y cargar» deja antes lo jugado en la ranura automática; «Cargar sin
 * guardar» lo descarta (y no toca AUTO). Cargar AUTO no ofrece guardar: sería pisar el estado que se va a cargar.
 */
@Composable
fun LoadStateConfirmDialog(
    slot: StateSlot,
    slotLabel: String,
    onSaveAndLoad: () -> Unit,
    onLoadWithoutSaving: () -> Unit,
    onCancel: () -> Unit,
) {
    val isAuto = slot == StateSlot.AUTO
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.gameplay_load_title, slotLabel)) },
        text = {
            Text(
                stringResource(
                    when (slot) {
                        StateSlot.AUTO -> R.string.gameplay_load_body_auto
                        StateSlot.RESCUE -> R.string.state_load_body_rescue
                        else -> R.string.gameplay_load_body
                    },
                ),
            )
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isAuto) {
                    Button(
                        onClick = onSaveAndLoad,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("state-confirm-save"),
                    ) { Text(stringResource(R.string.gameplay_load_save_first)) }
                }
                TextButton(
                    onClick = onLoadWithoutSaving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("state-confirm-nosave"),
                ) { Text(stringResource(if (isAuto) R.string.gameplay_load_auto_confirm else R.string.gameplay_load_without_saving)) }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("state-cancel"),
                ) { Text(stringResource(R.string.dialog_cancel)) }
            }
        },
        modifier = Modifier.testTag("state-load-dialog"),
    )
}
