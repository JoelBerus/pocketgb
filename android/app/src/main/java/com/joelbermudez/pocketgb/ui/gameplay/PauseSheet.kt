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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
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

/**
 * Menú de pausa (iOS `PauseView`): título = nombre del juego, Continuar, Estados guardados, Personalizar controles
 * y Salir (destructivo), con pies. Detrás queda el último fotograma atenuado.
 */
@Composable
fun PauseSheet(
    landscape: Boolean,
    busy: Boolean,
    title: String,
    onContinue: () -> Unit,
    onStates: () -> Unit,
    onCustomize: () -> Unit,
    onExit: () -> Unit,
    /** N5: «Usar como portada»; `null` la oculta. */
    onUseAsCover: (() -> Unit)? = null,
) {
    SheetOrDialog(landscape = landscape, onDismiss = onContinue, modifier = Modifier.testTag("pause-sheet")) {
        PauseMenuContent(
            busy = busy,
            title = title,
            onContinue = onContinue,
            onStates = onStates,
            onCustomize = onCustomize,
            onExit = onExit,
            onUseAsCover = onUseAsCover,
        )
    }
}

@Composable
fun PauseMenuContent(
    busy: Boolean,
    title: String,
    onContinue: () -> Unit,
    onStates: () -> Unit,
    onCustomize: () -> Unit,
    onExit: () -> Unit,
    onUseAsCover: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title.ifBlank { stringResource(R.string.gameplay_pause_fallback_title) },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            modifier = Modifier.testTag("pause-title"),
        )
        Button(
            onClick = onContinue,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pause-continue"),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.gameplay_pause_continue), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = onStates,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pause-states"),
        ) {
            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.n6_pause_moments), modifier = Modifier.padding(start = 8.dp))
        }
        com.joelbermudez.pocketgb.ui.tips.TipCard(com.joelbermudez.pocketgb.tips.Tip.MOMENTS)
        OutlinedButton(
            onClick = onCustomize,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pause-customize"),
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.gameplay_pause_customize), modifier = Modifier.padding(start = 8.dp))
        }
        if (onUseAsCover != null) {
            OutlinedButton(
                onClick = onUseAsCover,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pause-use-as-cover"),
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.n5_pause_use_as_cover), modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text(
            stringResource(R.string.n6_pause_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onExit,
            enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pause-exit"),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.gameplay_pause_exit), modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            stringResource(R.string.gameplay_pause_exit_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
