package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.input.ControlsOrientation
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.settings.OPACITY_CHOICES
import com.joelbermudez.pocketgb.settings.SIZE_SCALE_CHOICES
import com.joelbermudez.pocketgb.ui.settings.components.ChoiceRow
import com.joelbermudez.pocketgb.ui.settings.components.SettingsGroup
import com.joelbermudez.pocketgb.ui.settings.components.SettingsPage
import com.joelbermudez.pocketgb.ui.settings.components.SwitchRow

/** Ajustes › Controles: opacidad, cruceta, diagonales, tamaño, visibilidad, háptica y disposición. */
@Composable
fun ControlsSettingsScreen(repository: GameplaySettingsRepository, onBack: () -> Unit, onController: () -> Unit = {}) {
    val data by repository.state.collectAsStateWithLifecycle()
    val failure by repository.persistFailure.collectAsStateWithLifecycle()
    ControlsSettingsContent(data, repository::update, onBack, warning = persistWarning(failure != null), onController = onController)
}

@Composable
fun ControlsSettingsContent(
    data: GameplaySettingsData,
    onUpdate: ((GameplaySettingsData) -> GameplaySettingsData) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    warning: String? = null,
    onController: () -> Unit = {},
) {
    SettingsPage(stringResource(R.string.settings_controls), onBack, modifier, warning) {
        SettingsGroup(
            header = stringResource(R.string.controls_opacity_header),
            footer = stringResource(R.string.controls_opacity_footer),
        ) {
            ChoiceRow(
                title = stringResource(R.string.controls_opacity_label),
                options = OPACITY_CHOICES.map { it to stringResource(R.string.controls_opacity_value, it) },
                selected = data.opacity,
                onSelect = { value -> onUpdate { it.copy(opacity = value) } },
                tag = "controls-opacity",
            )
        }
        SettingsGroup(
            header = stringResource(R.string.controls_dpad_header),
            footer = stringResource(R.string.controls_dpad_footer),
        ) {
            ChoiceRow(
                title = stringResource(R.string.controls_dpad_label),
                options = listOf(
                    DpadStyle.CROSS to stringResource(R.string.controls_dpad_cross),
                    DpadStyle.ARROWS to stringResource(R.string.controls_dpad_arrows),
                ),
                selected = data.dpadStyle,
                onSelect = { value -> onUpdate { it.copy(dpadStyle = value) } },
                tag = "controls-dpad",
            )
        }
        DiagonalsGroup(data, onUpdate)
        SettingsGroup(header = stringResource(R.string.controls_group_header)) {
            ChoiceRow(
                title = stringResource(R.string.controls_size_label),
                options = SIZE_SCALE_CHOICES.zip(
                    listOf(
                        stringResource(R.string.controls_size_small),
                        stringResource(R.string.controls_size_normal),
                        stringResource(R.string.controls_size_large),
                    ),
                ),
                selected = data.sizeScale,
                onSelect = { value -> onUpdate { it.copy(sizeScale = value) } },
                tag = "controls-size",
            )
            ChoiceRow(
                title = stringResource(R.string.controls_visibility_label),
                options = listOf(
                    ControlsVisibility.ALWAYS to stringResource(R.string.controls_visibility_always),
                    ControlsVisibility.ON_TOUCH to stringResource(R.string.controls_visibility_on_touch),
                    ControlsVisibility.HIDDEN to stringResource(R.string.controls_visibility_hidden),
                ),
                selected = data.visibility,
                onSelect = { value -> onUpdate { it.copy(visibility = value) } },
                tag = "controls-visibility",
            )
            SwitchRow(
                title = stringResource(R.string.controls_haptics),
                checked = data.haptics,
                onCheckedChange = { value -> onUpdate { it.copy(haptics = value) } },
                tag = "controls-haptics",
            )
        }
        SettingsGroup(
            header = stringResource(R.string.controller_group_header),
            footer = stringResource(R.string.controller_group_footer),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.controller_assign_buttons)) },
                supportingContent = { Text(stringResource(R.string.controller_assign_summary)) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onController).testTag("controller-assign"),
            )
            SwitchRow(
                title = stringResource(R.string.controller_show_touch),
                checked = data.showTouchControlsWithController,
                onCheckedChange = { value -> onUpdate { it.copy(showTouchControlsWithController = value) } },
                tag = "controller-show-touch",
            )
        }
        SettingsGroup(
            header = stringResource(R.string.controls_layout_header),
            footer = stringResource(R.string.controls_layout_footer),
        ) {
            ResetLayoutRow(
                label = stringResource(R.string.controls_reset_portrait),
                enabled = !data.isFactoryLayout(ControlsOrientation.PORTRAIT),
                tag = "reset-layout-portrait",
            ) { onUpdate { it.resetLayout(ControlsOrientation.PORTRAIT) } }
            ResetLayoutRow(
                label = stringResource(R.string.controls_reset_landscape),
                enabled = !data.isFactoryLayout(ControlsOrientation.LANDSCAPE),
                tag = "reset-layout-landscape",
            ) { onUpdate { it.resetLayout(ControlsOrientation.LANDSCAPE) } }
        }
    }
}

/** Grupo «Diagonales» (N2): tres nombres largos no caben en botones segmentados, así que es una lista de opciones con su explicación. */
@Composable
internal fun DiagonalsGroup(
    data: GameplaySettingsData,
    onUpdate: ((GameplaySettingsData) -> GameplaySettingsData) -> Unit,
) {
    SettingsGroup(
        header = stringResource(R.string.controls_diagonals_header),
        footer = stringResource(R.string.controls_diagonals_footer),
    ) {
        // Tres nombres largos («Desactivadas») no caben en botones segmentados: lista de opciones con su explicación.
        Column(Modifier.selectableGroup()) {
            listOf(
                Triple(DiagonalMode.NORMAL, R.string.controls_diagonals_normal, R.string.controls_diagonals_normal_hint),
                Triple(DiagonalMode.REDUCED, R.string.controls_diagonals_reduced, R.string.controls_diagonals_reduced_hint),
                Triple(DiagonalMode.DISABLED, R.string.controls_diagonals_disabled, R.string.controls_diagonals_disabled_hint),
            ).forEachIndexed { index, (mode, label, hint) ->
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    supportingContent = { Text(stringResource(hint)) },
                    leadingContent = { RadioButton(selected = data.diagonalMode == mode, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .heightIn(min = 56.dp)
                        .selectable(
                            selected = data.diagonalMode == mode,
                            role = Role.RadioButton,
                            onClick = { onUpdate { it.copy(diagonalMode = mode) } },
                        )
                        .testTag("controls-diagonals-$index"),
                )
            }
        }
    }
}

@Composable
private fun ResetLayoutRow(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            leadingIconColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).testTag(tag),
    )
}

/** Aviso no bloqueante cuando no se pudo escribir en disco (se reintenta con el siguiente cambio). */
@Composable
fun persistWarning(failed: Boolean): String? =
    if (failed) stringResource(R.string.settings_persist_failed) else null
