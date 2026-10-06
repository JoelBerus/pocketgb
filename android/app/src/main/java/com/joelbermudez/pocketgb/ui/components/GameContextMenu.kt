package com.joelbermudez.pocketgb.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag("game-context-menu")) {
        if (entry.isPlayable && onPlay != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_play)) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = { onDismiss(); onPlay() },
            )
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
