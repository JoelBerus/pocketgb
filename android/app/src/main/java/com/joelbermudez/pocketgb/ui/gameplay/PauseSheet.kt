package com.joelbermudez.pocketgb.ui.gameplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.joelbermudez.pocketgb.R

/**
 * Contenedor común de las hojas de la partida: hoja inferior modal en vertical y diálogo centrado en
 * horizontal (SPEC §3.3). El contenido es el mismo y siempre se desplaza si no cabe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetOrDialog(
    landscape: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (landscape) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = modifier.fillMaxWidth(0.62f).widthIn(min = 280.dp).heightIn(max = 340.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 8.dp)) { content() }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = modifier,
        ) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) { content() }
        }
    }
}

/** Menú de pausa: Continuar, Estados y Salir. ("Editar controles" llega en A6.) */
@Composable
fun PauseSheet(
    landscape: Boolean,
    busy: Boolean,
    onContinue: () -> Unit,
    onStates: () -> Unit,
    onExit: () -> Unit,
) {
    SheetOrDialog(landscape = landscape, onDismiss = onContinue, modifier = Modifier.testTag("pause-sheet")) {
        PauseMenuContent(busy = busy, onContinue = onContinue, onStates = onStates, onExit = onExit)
    }
}

@Composable
fun PauseMenuContent(
    busy: Boolean,
    onContinue: () -> Unit,
    onStates: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.pause_title), style = MaterialTheme.typography.titleLarge)
        Button(
            onClick = onContinue,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pause-continue"),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.pause_continue), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = onStates,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pause-states"),
        ) {
            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.pause_states), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = onExit,
            enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pause-exit"),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.pause_exit), modifier = Modifier.padding(start = 8.dp))
        }
        if (busy) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.pause_saving), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Row(Modifier.padding(bottom = 8.dp)) {}
        }
    }
}
