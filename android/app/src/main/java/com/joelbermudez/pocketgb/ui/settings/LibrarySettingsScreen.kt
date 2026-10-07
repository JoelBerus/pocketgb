package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.LibraryError
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibrarySort
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.library.rememberFolderPicker
import com.joelbermudez.pocketgb.ui.settings.components.ChoiceRow
import com.joelbermudez.pocketgb.library.artwork.CoverPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.joelbermudez.pocketgb.ui.settings.components.DropdownRow
import com.joelbermudez.pocketgb.ui.settings.components.SettingsGroup

@Composable
fun LibrarySettingsScreen(viewModel: LibraryViewModel, onBack: () -> Unit, onOpenHome: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    val chooseFolder = rememberFolderPicker(viewModel::chooseFolder)
    val covers = com.joelbermudez.pocketgb.ui.components.rememberCoverRepository()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val entries = when (val current = state) {
        is LibraryState.Ready -> current.entries
        is LibraryState.Scanning -> current.previous
        else -> emptyList()
    }
    LibrarySettingsContent(
        state = state,
        folderName = folderName,
        hidden = remember(entries, prefs) { LibraryQuery.hidden(entries, prefs) },
        onChooseFolder = chooseFolder,
        onRescan = viewModel::rescan,
        onForget = viewModel::forgetFolder,
        onUnhide = viewModel::unhide,
        onBack = onBack,
        layout = prefs.layout,
        sort = prefs.sort,
        onLayout = viewModel::setLayout,
        onSort = viewModel::setSort,
        onOpenHome = onOpenHome,
        coverPreference = covers.settingsState.collectAsStateWithLifecycle().value.preference,
        onCoverPreference = { preference -> scope.launch(Dispatchers.IO) { covers.setPreference(preference) } },
    )
}

private fun statusText(state: LibraryState): String = when (state) {
    LibraryState.Loading -> "Cargando…"
    LibraryState.NoFolder -> "Sin carpeta elegida"
    is LibraryState.Scanning -> "Buscando juegos…"
    is LibraryState.Ready -> when (state.entries.size) {
        0 -> "Sin juegos"
        1 -> "1 juego"
        else -> "${state.entries.size} juegos"
    }
    is LibraryState.Failed -> when (state.error) {
        LibraryError.PermissionRevoked -> "Permiso revocado: vuelve a elegir la carpeta"
        LibraryError.FolderMissing -> "La carpeta ya no existe"
        LibraryError.Unreadable -> "No se pudo leer la carpeta"
        LibraryError.AccessNotKept -> "No se pudo conservar el acceso a la carpeta"
    }
}

/** Ajustes › Biblioteca sin ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsContent(
    state: LibraryState,
    folderName: String?,
    hidden: List<RomEntry>,
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit,
    onForget: () -> Unit,
    onUnhide: (RomEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    layout: LibraryLayout = LibraryLayout.GRID,
    sort: LibrarySort = LibrarySort.TITLE,
    onLayout: (LibraryLayout) -> Unit = {},
    onSort: (LibrarySort) -> Unit = {},
    /** N4: Ajustes › Biblioteca › Inicio; `null` no muestra la fila. */
    onOpenHome: (() -> Unit)? = null,
    /** N5: preferencia global de portadas; `null` no muestra la fila. */
    coverPreference: CoverPreference? = null,
    onCoverPreference: (CoverPreference) -> Unit = {},
) {
    var confirmForget by remember { mutableStateOf(false) }
    val hasFolder = state != LibraryState.NoFolder && state != LibraryState.Loading
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_library)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("library-settings"), contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.library_settings_folder_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { heading() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_library_current_folder)) },
                    supportingContent = {
                        Text(
                            if (hasFolder) "${folderName ?: "Sin nombre"} · ${statusText(state)}" else statusText(state),
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text(if (hasFolder) "Cambiar carpeta" else "Elegir carpeta") },
                    leadingContent = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onChooseFolder).testTag("settings-choose-folder"),
                )
            }
            if (hasFolder) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_library_rescan)) },
                        leadingContent = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onRescan).testTag("settings-rescan"),
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_library_forget)) },
                        supportingContent = { Text(stringResource(R.string.settings_library_forget_summary)) },
                        leadingContent = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        colors = ListItemDefaults.colors(
                            headlineColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.clickable { confirmForget = true }.testTag("settings-forget"),
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.library_settings_folder_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroup(
                    header = stringResource(R.string.library_settings_view_header),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    ChoiceRow(
                        title = stringResource(R.string.library_settings_layout),
                        options = LibraryLayout.entries.map { it to it.title },
                        selected = layout,
                        onSelect = onLayout,
                        tag = "library-layout",
                    )
                    DropdownRow(
                        title = stringResource(R.string.library_settings_sort),
                        options = LibrarySort.entries.map { it to it.title },
                        selected = sort,
                        onSelect = onSort,
                        tag = "library-sort",
                    )
                    if (coverPreference != null) {
                        ChoiceRow(
                            title = stringResource(R.string.n5_settings_covers),
                            options = listOf(
                                CoverPreference.IMAGES to stringResource(R.string.n5_settings_covers_images),
                                CoverPreference.CAPTURES to stringResource(R.string.n5_settings_covers_captures),
                            ),
                            selected = coverPreference,
                            onSelect = onCoverPreference,
                            tag = "library-covers",
                        )
                        Text(
                            stringResource(R.string.n5_settings_covers_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                    }
                    if (onOpenHome != null) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.n4_settings_home)) },
                            supportingContent = { Text(stringResource(R.string.n4_settings_home_summary)) },
                            leadingContent = { Icon(Icons.Outlined.Home, contentDescription = null) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable(onClick = onOpenHome).testTag("settings-library-home"),
                        )
                    }
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.settings_library_hidden_title), style = MaterialTheme.typography.titleMedium)
                    },
                )
            }
            if (hidden.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_library_hidden_none)) },
                        supportingContent = { Text(stringResource(R.string.settings_library_hidden_none_summary)) },
                        modifier = Modifier.testTag("settings-no-hidden"),
                    )
                }
            } else {
                items(hidden, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.displayTitle) },
                        supportingContent = { Text(entry.fileName, maxLines = 1) },
                        trailingContent = {
                            val showDescription = stringResource(R.string.library_settings_show_description, entry.displayTitle)
                            TextButton(
                                onClick = { onUnhide(entry) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = showDescription }
                                    .testTag("unhide-${entry.id}"),
                            ) {
                                Text(stringResource(R.string.library_settings_show))
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
            item {
                Text(
                    stringResource(R.string.library_settings_hidden_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.settings_library_forget_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_library_forget_dialog_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForget()
                }) { Text(stringResource(R.string.settings_library_forget_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.settings_library_forget_cancel)) } },
        )
    }
}
