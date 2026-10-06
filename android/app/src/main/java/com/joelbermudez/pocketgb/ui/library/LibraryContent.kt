package com.joelbermudez.pocketgb.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.LibraryError
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibrarySort
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.components.ContinueRail
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.ui.components.rememberArtworkFingerprints

/**
 * Pantalla de Biblioteca sin ViewModel: recibe estado y callbacks, así que el catálogo
 * debug y las pruebas Compose la ejercitan con datos sintéticos.
 *
 * [artworkFingerprints] son las huellas con portada capturada (carril «Continuar jugando», K10); `null` = leerlas
 * del almacén real. [newGamesSummary] > 0 muestra un aviso breve de juegos nuevos (K19) y luego llama a
 * [onNewGamesSummaryShown].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    state: LibraryState,
    prefs: LibraryPreferencesData,
    query: String,
    filter: LibraryFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit,
    actions: GameActions,
    modifier: Modifier = Modifier,
    newGamesSummary: Int = 0,
    onNewGamesSummaryShown: () -> Unit = {},
    artworkFingerprints: Set<String>? = null,
) {
    val showsGames = state is LibraryState.Ready && state.entries.isNotEmpty() ||
        state is LibraryState.Scanning && state.previous.isNotEmpty()
    val realFingerprints = rememberArtworkFingerprints()
    val withArtwork = artworkFingerprints ?: realFingerprints
    val snackbar = remember { SnackbarHostState() }
    val summaryText = pluralStringResource(R.plurals.library_new_games, newGamesSummary, newGamesSummary)
    LaunchedEffect(newGamesSummary) {
        if (newGamesSummary > 0) {
            snackbar.showSnackbar(summaryText)
            onNewGamesSummaryShown()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    if (showsGames) MoreMenu(prefs, onLayoutChange, onSortChange, onRescan, onChooseFolder)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (state) {
                LibraryState.Loading -> ScanningPane(message = stringResource(R.string.library_loading))
                LibraryState.NoFolder -> EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.library_pick_title),
                    message = stringResource(R.string.library_pick_message),
                    actions = { Button(onClick = onChooseFolder) { Text(stringResource(R.string.library_pick_action)) } },
                )
                is LibraryState.Scanning -> if (state.previous.isEmpty()) {
                    ScanningPane(message = scanMessage(state.done, state.total))
                } else {
                    GameBrowser(
                        entries = state.previous,
                        folderName = state.folderName,
                        prefs = prefs,
                        query = query,
                        filter = filter,
                        onQueryChange = onQueryChange,
                        onFilterChange = onFilterChange,
                        actions = actions,
                        scanning = true,
                        done = state.done,
                        total = state.total,
                        artworkFingerprints = withArtwork,
                        onRescan = onRescan,
                    )
                }
                is LibraryState.Ready -> if (state.entries.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.SportsEsports,
                        title = stringResource(R.string.library_empty_title),
                        message = stringResource(R.string.library_empty_message),
                        actions = {
                            Button(onClick = onRescan) { Text(stringResource(R.string.library_rescan)) }
                            OutlinedButton(onClick = onChooseFolder) { Text(stringResource(R.string.library_pick_other_action)) }
                        },
                    )
                } else {
                    GameBrowser(
                        entries = state.entries,
                        folderName = state.folderName,
                        prefs = prefs,
                        query = query,
                        filter = filter,
                        onQueryChange = onQueryChange,
                        onFilterChange = onFilterChange,
                        actions = actions,
                        scanning = false,
                        done = 0,
                        total = 0,
                        artworkFingerprints = withArtwork,
                        onRescan = onRescan,
                    )
                }
                is LibraryState.Failed -> LibraryErrorPane(state.error, onChooseFolder, onRescan)
            }
        }
    }
}

@Composable
private fun scanMessage(done: Int, total: Int): String =
    if (total > 0) stringResource(R.string.library_scanning_progress, done, total) else stringResource(R.string.library_scanning)

@Composable
internal fun ScanningPane(modifier: Modifier = Modifier, message: String = stringResource(R.string.library_scanning)) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.testTag("library-progress"))
        Text(
            message,
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun LibraryErrorPane(error: LibraryError, onChooseFolder: () -> Unit, onRescan: () -> Unit) {
    when (error) {
        LibraryError.PermissionRevoked -> EmptyState(
            icon = Icons.Outlined.ErrorOutline,
            title = stringResource(R.string.library_access_lost_title),
            message = stringResource(R.string.library_access_lost_message),
            actions = {
                Button(onClick = onChooseFolder) { Text(stringResource(R.string.library_choose_again)) }
                OutlinedButton(onClick = onRescan) { Text(stringResource(R.string.library_retry)) }
            },
        )
        LibraryError.FolderMissing -> EmptyState(
            icon = Icons.Outlined.FolderOff,
            title = stringResource(R.string.library_folder_missing_title),
            message = stringResource(R.string.library_folder_missing_message),
            actions = {
                Button(onClick = onChooseFolder) { Text(stringResource(R.string.library_pick_other_action)) }
                OutlinedButton(onClick = onRescan) { Text(stringResource(R.string.library_retry)) }
            },
        )
        LibraryError.AccessNotKept -> EmptyState(
            icon = Icons.Outlined.ErrorOutline,
            title = stringResource(R.string.library_persist_failed_title),
            message = stringResource(R.string.library_persist_failed_message),
            actions = {
                Button(onClick = onChooseFolder) { Text(stringResource(R.string.library_pick_action)) }
                OutlinedButton(onClick = onRescan) { Text(stringResource(R.string.library_retry)) }
            },
        )
        LibraryError.Unreadable -> EmptyState(
            icon = Icons.Outlined.ErrorOutline,
            title = stringResource(R.string.library_read_failed_title),
            message = stringResource(R.string.library_read_failed_message),
            actions = {
                Button(onClick = onRescan) { Text(stringResource(R.string.library_retry)) }
                OutlinedButton(onClick = onChooseFolder) { Text(stringResource(R.string.library_pick_other_action)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameBrowser(
    entries: List<RomEntry>,
    folderName: String?,
    prefs: LibraryPreferencesData,
    query: String,
    filter: LibraryFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    actions: GameActions,
    scanning: Boolean,
    done: Int,
    total: Int,
    artworkFingerprints: Set<String>,
    onRescan: () -> Unit,
) {
    val searching = query.isNotBlank()
    val visible = remember(entries, prefs, filter, query) { LibraryQuery.visible(entries, prefs, filter, query) }
    val rail = remember(entries, prefs, artworkFingerprints) {
        LibraryQuery.recent(entries, prefs, hasArtwork = { it in artworkFingerprints })
    }
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(scanning) { if (!scanning) pulled = false }
    Column(Modifier.fillMaxSize()) {
        if (scanning) ScanProgress(done, total)
        SearchField(query, onQueryChange)
        FilterRow(filter, onFilterChange)
        PullToRefreshBox(
            isRefreshing = pulled,
            onRefresh = {
                pulled = true
                onRescan()
            },
            modifier = Modifier.weight(1f),
        ) {
            when {
                visible.isEmpty() -> NoResults(
                    query = query,
                    filter = filter,
                    allHidden = filter == LibraryFilter.ALL && !searching,
                    onFilterChange = onFilterChange,
                )
                searching -> GameCollection(
                    entries = visible,
                    prefs = prefs,
                    layout = LibraryLayout.LIST,
                    actions = actions,
                    header = { ResultsCount(visible.size) },
                )
                else -> GameCollection(
                    entries = visible,
                    prefs = prefs,
                    layout = prefs.layout,
                    actions = actions,
                    header = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (filter == LibraryFilter.ALL && rail.isNotEmpty()) {
                                ContinueRail(
                                    entries = rail,
                                    prefs = prefs,
                                    onOpenDetails = actions.onOpenDetails,
                                    onContinue = actions.onPlay ?: actions.onOpenDetails,
                                )
                            }
                            SectionHeader(
                                title = if (filter == LibraryFilter.ALL) {
                                    stringResource(R.string.library_all_games)
                                } else {
                                    filter.title
                                },
                                folderName = folderName,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ScanProgress(done: Int, total: Int) {
    val description = stringResource(R.string.library_updating_description)
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        val modifier = Modifier.fillMaxWidth().testTag("library-progress")
        if (total > 0) {
            LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = modifier)
        } else {
            LinearProgressIndicator(modifier = modifier)
        }
        Text(
            scanMessage(done, total),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String, folderName: String?) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("library-section-title"))
        if (folderName != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    folderName,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.width(160.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun ResultsCount(count: Int) {
    Text(
        if (count == 1) stringResource(R.string.library_results_one) else stringResource(R.string.library_results_many, count),
        modifier = Modifier.testTag("library-results-count"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `true` enfoca el campo de búsqueda al abrirse (teclado visible); solo lo fija el catálogo de capturas. */
val LocalAutoFocusSearch = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (LocalAutoFocusSearch.current) LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focus).testTag("library-search"),
        singleLine = true,
        label = { Text(stringResource(R.string.library_search_label)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.library_search_clear))
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun FilterRow(filter: LibraryFilter, onFilterChange: (LibraryFilter) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryFilter.entries.forEach { option ->
            FilterChip(
                selected = filter == option,
                onClick = { onFilterChange(option) },
                label = { Text(option.title) },
                leadingIcon = if (filter == option) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
                modifier = Modifier.testTag("filter-${option.name}"),
            )
        }
    }
}

@Composable
private fun NoResults(
    query: String,
    filter: LibraryFilter,
    allHidden: Boolean,
    onFilterChange: (LibraryFilter) -> Unit,
) {
    when {
        query.isNotBlank() && filter == LibraryFilter.ALL -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.library_no_results_title, query.trim()),
            message = stringResource(R.string.library_no_results_all),
        )
        query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.library_no_results_title, query.trim()),
            message = stringResource(R.string.library_no_results_filter, filter.title),
            actions = {
                Button(onClick = { onFilterChange(LibraryFilter.ALL) }) {
                    Text(stringResource(R.string.library_search_all))
                }
            },
        )
        allHidden -> EmptyState(
            icon = Icons.Outlined.VisibilityOff,
            title = stringResource(R.string.library_all_hidden_title),
            message = stringResource(R.string.library_all_hidden_message),
        )
        filter == LibraryFilter.FAVORITES -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.library_no_favorites_title),
            message = stringResource(R.string.library_no_favorites_message),
            actions = {
                Button(onClick = { onFilterChange(LibraryFilter.ALL) }) { Text(stringResource(R.string.library_show_all)) }
            },
        )
        else -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.library_no_games_filter_title, filter.title),
            message = stringResource(R.string.library_no_games_filter_message),
            actions = {
                Button(onClick = { onFilterChange(LibraryFilter.ALL) }) { Text(stringResource(R.string.library_show_all)) }
            },
        )
    }
}

/** «Más opciones»: vista, orden, volver a escanear y cambiar de carpeta. */
@Composable
private fun MoreMenu(
    prefs: LibraryPreferencesData,
    onLayoutChange: (LibraryLayout) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onRescan: () -> Unit,
    onChooseFolder: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("view-menu")) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuHeader(stringResource(R.string.library_view))
            LibraryLayout.entries.forEach { layout ->
                MenuChoice(layout.title, selected = prefs.layout == layout) {
                    onLayoutChange(layout)
                    expanded = false
                }
            }
            HorizontalDivider()
            MenuHeader(stringResource(R.string.library_sort_by))
            LibrarySort.entries.forEach { sort ->
                MenuChoice(sort.title, selected = prefs.sort == sort) {
                    onSortChange(sort)
                    expanded = false
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_rescan)) },
                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRescan()
                },
                modifier = Modifier.testTag("menu-rescan"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_change_folder)) },
                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                onClick = {
                    expanded = false
                    onChooseFolder()
                },
                modifier = Modifier.testTag("menu-change-folder"),
            )
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.library_selected)) }
        } else {
            { Box(Modifier.width(24.dp)) }
        },
    )
}
