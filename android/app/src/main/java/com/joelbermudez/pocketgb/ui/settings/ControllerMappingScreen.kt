package com.joelbermudez.pocketgb.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.input.PadAction
import com.joelbermudez.pocketgb.settings.ControllerMappingData
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.settings.components.SettingsGroup
import com.joelbermudez.pocketgb.ui.settings.components.SettingsPage

/** Ajustes › Controles › Asignar botones: qué botón del mando hace cada acción. */
@Composable
fun ControllerMappingScreen(repository: GameplaySettingsRepository, onBack: () -> Unit) {
    val data by repository.state.collectAsStateWithLifecycle()
    val failure by repository.persistFailure.collectAsStateWithLifecycle()
    ControllerMappingContent(data, repository::update, onBack, warning = persistWarning(failure != null))
}

@Composable
fun ControllerMappingContent(
    data: GameplaySettingsData,
    onUpdate: ((GameplaySettingsData) -> GameplaySettingsData) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    warning: String? = null,
) {
    var assigning by remember { mutableStateOf<PadAction?>(null) }
    val resolved = (data.controllerMapping ?: ControllerMappingData.DEFAULT).resolved()
    SettingsPage(stringResource(R.string.controller_mapping_title), onBack, modifier, warning) {
        SettingsGroup(
            header = stringResource(R.string.controller_mapping_header),
            footer = stringResource(R.string.controller_mapping_footer),
        ) {
            PadAction.entries.forEach { action ->
                val keys = resolved.filterValues { it == action }.keys.map { padKeyLabel(it) }
                ListItem(
                    headlineContent = { Text(padActionLabel(action)) },
                    supportingContent = {
                        Text(
                            if (keys.isEmpty()) stringResource(R.string.controller_unassigned)
                            else keys.joinToString(" / "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("pad-key-${action.name}"),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.testTag("pad-row-${action.name}").clickable { assigning = action },
                )
            }
        }
        SettingsGroup {
            ListItem(
                headlineContent = { Text(stringResource(R.string.controller_reset)) },
                leadingContent = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .testTag("pad-reset")
                    .clickable(enabled = data.controllerMapping != null) { onUpdate { it.copy(controllerMapping = null) } },
            )
        }
    }
    assigning?.let { action ->
        AssignDialog(
            action = action,
            onKey = { code ->
                onUpdate { it.copy(controllerMapping = ControllerMappingData.assign(it.controllerMapping, action, code)) }
                assigning = null
            },
            onDismiss = { assigning = null },
        )
    }
}

/** Captura el siguiente botón de mando; cualquier otra tecla se ignora y Atrás cancela. */
@Composable
private fun AssignDialog(action: PadAction, onKey: (Int) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.controller_assign_dialog_title)) },
        text = {
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val code = event.nativeKeyEvent.keyCode
                        if (event.type == KeyEventType.KeyDown && KeyEvent.isGamepadButton(code) &&
                            ControllerMappingData.isAssignableKey(code)
                        ) {
                            onKey(code)
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("assign-dialog"),
            ) {
                Text(stringResource(R.string.controller_assign_dialog_message, padActionLabel(action)))
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.controller_assign_cancel)) } },
    )
}

@Composable
private fun padActionLabel(action: PadAction): String = stringResource(
    when (action) {
        PadAction.A -> R.string.pad_action_a
        PadAction.B -> R.string.pad_action_b
        PadAction.START -> R.string.pad_action_start
        PadAction.SELECT -> R.string.pad_action_select
        PadAction.MENU -> R.string.pad_action_menu
        PadAction.FAST_FORWARD -> R.string.pad_action_fast_forward
    },
)

@Composable
private fun padKeyLabel(code: Int): String = when (code) {
    KeyEvent.KEYCODE_BUTTON_A -> stringResource(R.string.pad_key_a)
    KeyEvent.KEYCODE_BUTTON_B -> stringResource(R.string.pad_key_b)
    KeyEvent.KEYCODE_BUTTON_X -> stringResource(R.string.pad_key_x)
    KeyEvent.KEYCODE_BUTTON_Y -> stringResource(R.string.pad_key_y)
    KeyEvent.KEYCODE_BUTTON_L1 -> stringResource(R.string.pad_key_l1)
    KeyEvent.KEYCODE_BUTTON_R1 -> stringResource(R.string.pad_key_r1)
    KeyEvent.KEYCODE_BUTTON_L2 -> stringResource(R.string.pad_key_l2)
    KeyEvent.KEYCODE_BUTTON_R2 -> stringResource(R.string.pad_key_r2)
    KeyEvent.KEYCODE_BUTTON_START -> stringResource(R.string.pad_key_start)
    KeyEvent.KEYCODE_BUTTON_SELECT -> stringResource(R.string.pad_key_select)
    KeyEvent.KEYCODE_BUTTON_MODE -> stringResource(R.string.pad_key_mode)
    KeyEvent.KEYCODE_BUTTON_THUMBL -> stringResource(R.string.pad_key_thumb_l)
    KeyEvent.KEYCODE_BUTTON_THUMBR -> stringResource(R.string.pad_key_thumb_r)
    else -> stringResource(R.string.pad_key_other, KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_"))
}
