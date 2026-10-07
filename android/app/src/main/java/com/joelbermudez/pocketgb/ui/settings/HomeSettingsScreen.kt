package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.HomeCategoryRow
import com.joelbermudez.pocketgb.library.HomeSettings
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryHome
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.ui.settings.components.SettingsGroup
import com.joelbermudez.pocketgb.ui.settings.components.SettingsPage
import com.joelbermudez.pocketgb.ui.settings.components.SwitchRow

/** N4 · Ajustes › Biblioteca › Inicio con datos reales (por dispositivo, ND12). */
@Composable
fun HomeSettingsScreen(viewModel: LibraryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val entries = when (val current = state) {
        is LibraryState.Ready -> current.entries
        is LibraryState.Scanning -> current.previous
        else -> emptyList()
    }
    val rows = remember(entries, prefs) { LibraryHome.arrangement(entries, prefs) }
    val keys = remember(entries, prefs) { LibraryHome.keys(entries, prefs) }
    HomeSettingsContent(
        rows = rows,
        showFavorites = prefs.home.showFavorites,
        onShowFavorites = { value -> viewModel.updateHome { it.copy(showFavorites = value) } },
        onMove = { key, offset -> viewModel.updateHome { it.move(key, offset, keys) } },
        onPinned = { key, value -> viewModel.updateHome { it.withPinned(key, value) } },
        onHidden = { key, value -> viewModel.updateHome { it.withHidden(key, value) } },
        onReset = { viewModel.updateHome { HomeSettings() } },
        onBack = onBack,
        changed = prefs.home != HomeSettings(),
    )
}

/**
 * Ajustes › Biblioteca › Inicio sin ViewModel: la fila de Favoritos y, por cada categoría de primer nivel (en el orden
 * del inicio), fijarla arriba, subirla o bajarla (dentro de las fijadas o de las demás) y mostrarla u ocultarla.
 * Con fuente grande los controles van bajo el nombre.
 */
@Composable
fun HomeSettingsContent(
    rows: List<HomeCategoryRow>,
    showFavorites: Boolean,
    onShowFavorites: (Boolean) -> Unit,
    onMove: (key: String, offset: Int) -> Unit,
    onPinned: (key: String, value: Boolean) -> Unit,
    onHidden: (key: String, value: Boolean) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    changed: Boolean = false,
) {
    SettingsPage(title = stringResource(R.string.n4_settings_home), onBack = onBack, modifier = modifier.testTag("home-settings")) {
        SettingsGroup(footer = stringResource(R.string.n4_home_favorites_footer)) {
            SwitchRow(
                title = stringResource(R.string.n4_home_favorites_row),
                checked = showFavorites,
                onCheckedChange = onShowFavorites,
                tag = "home-show-favorites",
            )
        }
        SettingsGroup(
            header = stringResource(R.string.n4_home_categories_header),
            footer = stringResource(R.string.n4_home_footer),
        ) {
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.n4_home_no_categories),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp).testTag("home-no-categories"),
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                val group = rows.filter { it.pinned == row.pinned }
                val position = group.indexOf(row)
                CategoryArrangementRow(
                    row = row,
                    canMoveUp = position > 0,
                    canMoveDown = position < group.lastIndex,
                    onMove = { onMove(row.key, it) },
                    onPinned = { onPinned(row.key, it) },
                    onHidden = { onHidden(row.key, it) },
                )
            }
        }
        if (changed) {
            OutlinedButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp).testTag("home-reset")) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.n4_home_reset), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun CategoryArrangementRow(
    row: HomeCategoryRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onPinned: (Boolean) -> Unit,
    onHidden: (Boolean) -> Unit,
) {
    val name = when (val category = row.category) {
        is LibraryCategory.Folder -> category.name
        else -> stringResource(R.string.n3_category_root)
    }
    val count = pluralStringResource(R.plurals.n4_games, row.count, row.count)
    val status = listOfNotNull(
        count,
        stringResource(R.string.n4_home_pinned_state).takeIf { row.pinned },
        stringResource(R.string.n4_home_hidden_state).takeIf { row.hidden },
    ).joinToString(" · ")
    val tag = "home-row-${row.key}"
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag)) {
        Row(
            Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (row.category is LibraryCategory.Folder) Icons.Outlined.Folder else Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pinDescription = stringResource(if (row.pinned) R.string.n4_home_unpin else R.string.n4_home_pin, name)
            IconToggleButton(
                checked = row.pinned,
                onCheckedChange = onPinned,
                modifier = Modifier.testTag("$tag-pin").semantics { contentDescription = pinDescription },
            ) {
                Icon(if (row.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = null)
            }
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.testTag("$tag-up")) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.n4_home_up, name))
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.testTag("$tag-down")) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.n4_home_down, name))
            }
            val showDescription = stringResource(R.string.n4_home_show_description, name)
            val shownState = stringResource(if (row.hidden) R.string.n4_home_hidden_state else R.string.n4_home_show)
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .toggleable(value = !row.hidden, role = Role.Switch) { onHidden(!it) }
                    .semantics {
                        contentDescription = showDescription
                        stateDescription = shownState
                    }
                    .testTag("$tag-show"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Text(stringResource(R.string.n4_home_show), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                Switch(checked = !row.hidden, onCheckedChange = null)
            }
        }
    }
}
