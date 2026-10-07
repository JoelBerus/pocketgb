package com.joelbermudez.pocketgb.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.RomEntry

/**
 * Acciones de la pulsación larga. Nunca ofrece borrar el ROM. «Estados» queda deshabilitado hasta que existan.
 * [onPlay] `null` (o juego con problema) oculta «Jugar»; [onGameSettings] `null` oculta «Ajustes del juego».
 * A9: con [canResume] la primera acción es «Continuar» (estado automático exacto) seguida de «Jugar desde el inicio»
 * ([onPlayFromStart]), como iOS; [onRename] `null` oculta «Renombrar».
 */
@Composable
fun GameContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entry: RomEntry,
    favorite: Boolean,
    onPlay: (() -> Unit)?,
    onOpenDetails: () -> Unit,
    onToggleFavorite: () -> Unit,
    onGameSettings: (() -> Unit)?,
    onHide: () -> Unit,
    canResume: Boolean = false,
    onPlayFromStart: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag("game-context-menu")) {
        if (entry.isPlayable && onPlay != null) {
            DropdownMenuItem(
                text = { Text(stringResource(if (canResume) R.string.a9_continue else R.string.menu_play)) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = { onDismiss(); onPlay() },
                modifier = Modifier.testTag("menu-play"),
            )
            if (canResume && onPlayFromStart != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.a9_play_from_start)) },
                    leadingIcon = { Icon(Icons.Filled.Replay, contentDescription = null) },
                    onClick = { onDismiss(); onPlayFromStart() },
                    modifier = Modifier.testTag("menu-play-from-start"),
                )
            }
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_details)) },
            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            onClick = { onDismiss(); onOpenDetails() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (favorite) R.string.menu_favorite_remove else R.string.menu_favorite_add)) },
            leadingIcon = {
                Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null)
            },
            onClick = { onDismiss(); onToggleFavorite() },
        )
        if (onRename != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.a9_rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { onDismiss(); onRename() },
                modifier = Modifier.testTag("menu-rename"),
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_states_soon)) },
            leadingIcon = { Icon(Icons.Outlined.ViewAgenda, contentDescription = null) },
            enabled = false,
            onClick = {},
        )
        if (onGameSettings != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_game_settings)) },
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                onClick = { onDismiss(); onGameSettings() },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_hide)) },
            leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            ),
            onClick = { onDismiss(); onHide() },
        )
    }
}
