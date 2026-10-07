package com.joelbermudez.pocketgb.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.components.RenameGameHost
import com.joelbermudez.pocketgb.ui.settings.SettingRowLabel
import com.joelbermudez.pocketgb.ui.settings.compatPaletteOptions
import com.joelbermudez.pocketgb.ui.settings.compatPaletteTitle

/**
 * Aloja la hoja de ajustes de [entry] (o nada si es `null`). Los ajustes por juego se guardan por huella: si aún
 * no se conoce (juego nunca abierto ni visto en detalle) se calcula leyendo el ROM antes de mostrar la hoja (K6).
 */
@Composable
fun GameSettingsHost(
    entry: RomEntry?,
    library: LibraryViewModel,
    repository: GameplaySettingsRepository,
    onDismiss: () -> Unit,
) {
    if (entry == null) return
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val settings by repository.state.collectAsStateWithLifecycle()
    var unavailable by remember(entry.id) { mutableStateOf(false) }
    var renaming by remember(entry.id) { mutableStateOf(false) }
    val fingerprint = prefs.fingerprints[entry.id]
    // N1-H1: una huella heredada de un movimiento (sin leer el ROM) se confirma antes de leer o escribir ajustes.
    val confirmed = fingerprint != null && prefs.hasConfirmedFingerprint(entry)
    LaunchedEffect(entry.id, confirmed) {
        if (!confirmed && library.loadDetails(entry.id) is DetailsLoad.Failed) unavailable = true
    }
    val shown = prefs.withAlias(entry)
    GameSettingsSheet(
        title = shown.displayTitle,
        headerTitle = entry.title,
        onRename = { renaming = true },
        isColor = entry.isColor,
        global = settings,
        overrides = fingerprint?.takeIf { confirmed }?.let { settings.perGame[it] } ?: GameOverrides(),
        onOverridesChange = { next ->
            if (confirmed && fingerprint != null) repository.update { it.setOverrides(fingerprint, next) }
        },
        onDismiss = onDismiss,
        loading = !confirmed && !unavailable,
        unavailable = !confirmed && unavailable,
    )
    RenameGameHost(
        entry = shown.takeIf { renaming },
        prefs = prefs,
        onSetAlias = library::setAlias,
        onDismiss = { renaming = false },
    )
}

/**
 * Ajustes de un juego (K8): cada valor dice con texto si es «Global» o «Personalizado». Se aplican al abrir
 * el juego. Un juego de Game Boy Color va siempre en color: su sección está deshabilitada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSettingsSheet(
    title: String,
    isColor: Boolean,
    global: GameplaySettingsData,
    overrides: GameOverrides,
    onOverridesChange: (GameOverrides) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    unavailable: Boolean = false,
    /** A9: título de la cabecera (se muestra bajo el nombre si el juego está renombrado). */
    headerTitle: String? = null,
    /** A9: fila «Nombre» con «Renombrar»; `null` la oculta. */
    onRename: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier.testTag("game-settings"),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.game_settings_title, title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("game-settings-done")) {
                    Text(stringResource(R.string.game_settings_done))
                }
            }
            if (onRename != null) NameRow(title, headerTitle, onRename)
            when {
                loading -> Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.game_settings_loading))
                }
                unavailable -> Text(
                    stringResource(R.string.game_settings_unavailable),
                    modifier = Modifier.padding(16.dp).testTag("game-settings-unavailable"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> SettingsBody(isColor, global, overrides, onOverridesChange)
            }
        }
    }
}

/** «Nombre»: el nombre visible del juego (alias o cabecera) y «Renombrar» (A9). Solo presentación. */
@Composable
private fun NameRow(title: String, headerTitle: String?, onRename: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.a9_game_settings_name)) },
        supportingContent = {
            Column {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (headerTitle != null && headerTitle != title) {
                    Text(
                        stringResource(R.string.a9_game_settings_name_header, headerTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            TextButton(onClick = onRename, modifier = Modifier.heightIn(min = 48.dp).testTag("game-settings-rename")) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.a9_rename), modifier = Modifier.padding(start = 6.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("game-settings-name"),
    )
}

@Composable
private fun SettingsBody(
    isColor: Boolean,
    global: GameplaySettingsData,
    overrides: GameOverrides,
    onOverridesChange: (GameOverrides) -> Unit,
) {
    val effectiveColor = overrides.colorForGameBoy ?: global.colorForGameBoy
    Text(
        stringResource(if (isColor) R.string.game_settings_header_gbc else R.string.game_settings_header_dmg),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("game-settings-header"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.alpha(if (isColor) 0.5f else 1f)) {
        GameSettingRow(
            title = stringResource(R.string.game_settings_color),
            customized = overrides.colorForGameBoy != null,
            options = listOf(
                null to stringResource(
                    if (global.colorForGameBoy) R.string.game_settings_global_color_on else R.string.game_settings_global_color_off,
                ),
                true to stringResource(R.string.game_settings_color_on),
                false to stringResource(R.string.game_settings_color_off),
            ),
            selected = overrides.colorForGameBoy,
            onSelect = { onOverridesChange(overrides.copy(colorForGameBoy = it)) },
            enabled = !isColor,
            tag = "game-setting-color",
        )
        GameSettingRow(
            title = stringResource(R.string.game_settings_palette),
            customized = overrides.compatPalette != null,
            options = listOf<Pair<Int?, String>>(
                null to stringResource(R.string.game_settings_global_palette, compatPaletteTitle(global.compatPalette)),
            ) + compatPaletteOptions().map { (id, name) -> id to name },
            selected = overrides.compatPalette,
            onSelect = { onOverridesChange(overrides.copy(compatPalette = it)) },
            enabled = !isColor && effectiveColor,
            tag = "game-setting-palette",
        )
    }
    Text(
        stringResource(R.string.game_settings_footer),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!overrides.isEmpty) {
        OutlinedButton(
            onClick = { onOverridesChange(GameOverrides()) },
            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("game-settings-reset"),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.game_settings_reset), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Fila de elección con el origen del valor («Global»/«Personalizado») en texto y un menú desplegable. */
@Composable
private fun <T> GameSettingRow(
    title: String,
    customized: Boolean,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    tag: String,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Box {
        ListItem(
            headlineContent = { SettingRowLabel(title, customized) },
            supportingContent = { Text(current) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .testTag(tag),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-$index"),
                )
            }
        }
    }
}
