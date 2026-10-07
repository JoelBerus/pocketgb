package com.joelbermudez.pocketgb.ui.gameplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ButtonDefaults
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.ControlsOrientation
import com.joelbermudez.pocketgb.settings.MAX_CONTROL_SCALE
import com.joelbermudez.pocketgb.settings.MAX_DPAD_SEPARATION
import com.joelbermudez.pocketgb.settings.MIN_CONTROL_SCALE
import com.joelbermudez.pocketgb.settings.MIN_DPAD_SEPARATION
import kotlin.math.roundToInt

private val EditorScrim = Color(0x99000000)

@Composable
fun controlName(id: ControlId): String = stringResource(
    when (id) {
        ControlId.DPAD -> R.string.editor_control_dpad
        ControlId.A -> R.string.editor_control_a
        ControlId.B -> R.string.editor_control_b
        ControlId.START -> R.string.editor_control_start
        ControlId.SELECT -> R.string.editor_control_select
        ControlId.MENU -> R.string.editor_control_menu
    },
)

/**
 * Barra del editor de controles (K12, `customize-controls-*`): orientación que se edita, Restablecer, Listo y, con un
 * control elegido, − / + de su tamaño en pasos del 10 %. Con la cruceta elegida y el estilo de flechas separadas
 * ([showSeparation]) añade − / + de la separación entre las flechas, de 70 % a 150 % (N2, ND10). Arrastrar y tocar los
 * controles lo resuelve el lienzo ([com.joelbermudez.pocketgb.input.GameControlsView] en modo edición); el juego sigue
 * en pausa.
 */
@Composable
fun ControlsEditorBar(
    orientation: ControlsOrientation,
    selected: ControlId?,
    selectedScale: Float,
    onReset: () -> Unit,
    onDone: () -> Unit,
    onSmaller: () -> Unit,
    onLarger: () -> Unit,
    modifier: Modifier = Modifier,
    showSeparation: Boolean = false,
    separation: Float = 1f,
    onCloser: () -> Unit = {},
    onFarther: () -> Unit = {},
) {
    val landscape = orientation == ControlsOrientation.LANDSCAPE
    Column(
        modifier.padding(16.dp).testTag("controls-editor"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Pill(stringResource(if (landscape) R.string.editor_title_landscape else R.string.editor_title_portrait))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onReset,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = EditorScrim, contentColor = Color.White),
                modifier = Modifier.heightIn(min = 48.dp).testTag("editor-reset"),
            ) {
                Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.editor_reset), modifier = Modifier.padding(start = 6.dp))
            }
            Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-done")) {
                Text(stringResource(R.string.editor_done))
            }
        }
        if (selected != null) {
            val percent = (selectedScale * 100f).roundToInt()
            val name = controlName(selected)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton(
                    Icons.Filled.Remove,
                    stringResource(R.string.editor_smaller),
                    enabled = selectedScale > MIN_CONTROL_SCALE + 0.001f,
                    onClick = onSmaller,
                    tag = "editor-smaller",
                )
                Pill(
                    stringResource(R.string.editor_size, name, percent),
                    modifier = Modifier
                        .semantics { contentDescription = "" }
                        .testTag("editor-size"),
                )
                StepButton(
                    Icons.Filled.Add,
                    stringResource(R.string.editor_larger),
                    enabled = selectedScale < MAX_CONTROL_SCALE - 0.001f,
                    onClick = onLarger,
                    tag = "editor-larger",
                )
            }
        }
        if (selected == ControlId.DPAD && showSeparation) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton(
                    Icons.Filled.Remove,
                    stringResource(R.string.editor_separation_closer),
                    enabled = separation > MIN_DPAD_SEPARATION + 0.001f,
                    onClick = onCloser,
                    tag = "editor-closer",
                )
                Pill(
                    stringResource(R.string.editor_separation, (separation * 100f).roundToInt()),
                    modifier = Modifier
                        .semantics { contentDescription = "" }
                        .testTag("editor-separation"),
                )
                StepButton(
                    Icons.Filled.Add,
                    stringResource(R.string.editor_separation_farther),
                    enabled = separation < MAX_DPAD_SEPARATION - 0.001f,
                    onClick = onFarther,
                    tag = "editor-farther",
                )
            }
        }
        Text(
            stringResource(if (landscape) R.string.editor_hint_landscape else R.string.editor_hint_portrait),
            color = Color.White,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            // Ancho acotado: en horizontal la barra queda entre la cruceta y A/B sin tapar ninguno de los dos.
            modifier = Modifier.widthIn(max = 320.dp).background(EditorScrim, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .background(EditorScrim, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = EditorScrim,
            contentColor = Color.White,
            disabledContainerColor = EditorScrim,
            disabledContentColor = Color(0x66FFFFFF),
        ),
        modifier = Modifier.size(48.dp).background(Color.Transparent, CircleShape).testTag(tag),
    ) { Icon(icon, contentDescription = label) }
}
