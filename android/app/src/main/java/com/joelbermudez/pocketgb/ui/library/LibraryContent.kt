package com.joelbermudez.pocketgb.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.CategoryOption
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryError
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibrarySort
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.continueRail
import com.joelbermudez.pocketgb.library.visible
import com.joelbermudez.pocketgb.ui.components.ContinueRail
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.ui.components.bleedHorizontally
import com.joelbermudez.pocketgb.ui.components.rememberArtworkFingerprints
import kotlin.math.roundToInt

/** N1-H2: aviso fijo sobre la biblioteca cuando sus preferencias son de otra versión de la app. */
@Composable
private fun PreferencesReadOnlyBanner() {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("library-prefs-read-only"),
    ) {
        Text(
            stringResource(R.string.n1_prefs_read_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * Pantalla de Biblioteca sin ViewModel: recibe estado y callbacks, así que el catálogo
 * debug y las pruebas Compose la ejercitan con datos sintéticos.
 *
 * [artworkFingerprints] son las huellas con portada capturada (carril «Continuar jugando», K10); `null` = leerlas
 * del almacén real. [newGamesSummary] > 0 muestra un aviso breve de juegos nuevos (K19) y luego llama a
 * [onNewGamesSummaryShown].
 *
 * N3b: si el espacio es más ancho que alto ([isLandscapeLibrary]) la búsqueda y los chips dejan la parte de arriba, la
 * barra superior se pliega al desplazar, el título de sección se queda fijo y a la derecha aparece la barra flotante
 * ([LibraryToolbar]: Buscar, Filtros, Categorías, Vista y orden). En vertical la estructura es la de siempre.
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
    /** N1-H2: `preferences.json` es de otra versión: aviso fijo de que los cambios no se guardarán. */
    preferencesReadOnly: Boolean = false,
    /** N3b: categoría elegida (carpeta de primer nivel). */
    category: LibraryCategory = LibraryCategory.All,
    onCategoryChange: (LibraryCategory) -> Unit = {},
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
    val shownEntries = when (state) {
        is LibraryState.Ready -> state.entries
        is LibraryState.Scanning -> state.previous
        else -> emptyList()
    }
    val categories = remember(shownEntries, prefs) { LibraryCategory.options(shownEntries, prefs) }
    val tools = rememberLibraryToolsState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = isLandscapeLibrary(maxWidth.value, maxHeight.value)
        // Alto visible de la lista con la barra superior desplegada (estable mientras la barra se pliega).
        val viewportHeight = (maxHeight - TopAppBarDefaults.TopAppBarExpandedHeight).value
        val searchVisible = landscape && showsGames && (tools.searchOpen || query.isNotEmpty())
        val closeSearch = {
            onQueryChange("")
            tools.searchOpen = false
            scrollBehavior.state.heightOffset = 0f
        }
        BackHandler(enabled = searchVisible) { closeSearch() }
        // Al pasar de horizontal a vertical se cierra un panel abierto, y la búsqueda horizontal si ya no hay texto (en
        // vertical se busca con el campo de siempre). Solo en ese paso: si la pantalla empieza en vertical y gira después,
        // no se toca nada.
        var wasLandscape by remember { mutableStateOf(landscape) }
        LaunchedEffect(landscape) {
            if (wasLandscape && !landscape) {
                tools.panel = null
                if (query.isEmpty()) tools.searchOpen = false
            }
            wasLandscape = landscape
        }
        Scaffold(
            modifier = if (landscape) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
            topBar = {
                when {
                    searchVisible -> LandscapeSearchBar(query, onQueryChange, onClose = closeSearch)
                    landscape -> TopAppBar(
                        title = { Text(stringResource(R.string.library_title)) },
                        actions = {
                            if (showsGames) {
                                MoreMenu(prefs, onLayoutChange, onSortChange, onRescan, onChooseFolder, viewOptions = false)
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                    else -> TopAppBar(
                        title = { Text(stringResource(R.string.library_title)) },
                        actions = {
                            if (showsGames) {
                                MoreMenu(
                                    prefs, onLayoutChange, onSortChange, onRescan, onChooseFolder,
                                    viewOptions = true,
                                    categories = categories,
                                    category = category,
                                    onCategoryChange = onCategoryChange,
                                )
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (landscape && showsGames && !searchVisible) {
                    LibraryToolbar(
                        tools = tools,
                        filter = filter,
                        category = category,
                        categories = categories,
                        layout = prefs.layout,
                        sort = prefs.sort,
                        onSearch = { tools.searchOpen = true },
                        onFilterChange = onFilterChange,
                        onCategoryChange = onCategoryChange,
                        onLayoutChange = onLayoutChange,
                        onSortChange = onSortChange,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (preferencesReadOnly) PreferencesReadOnlyBanner()
            Box(Modifier.fillMaxSize()) {
                val browser = BrowserMode(
                    landscape = landscape,
                    category = category,
                    onCategoryChange = onCategoryChange,
                    viewportHeightDp = viewportHeight,
                    tools = tools,
                )
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
                            mode = browser,
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
                            mode = browser,
                        )
                    }
                    is LibraryState.Failed -> LibraryErrorPane(state.error, onChooseFolder, onRescan)
                }
            }
            }
        }
    }
}

/** Lo que cambia la lista en horizontal (N3b) y la categoría elegida. */
private class BrowserMode(
    val landscape: Boolean,
    val category: LibraryCategory,
    val onCategoryChange: (LibraryCategory) -> Unit,
    /** Alto visible de la lista con la barra superior desplegada: limita el alto del carril en horizontal. */
    val viewportHeightDp: Float,
    val tools: LibraryToolsState,
)

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
    mode: BrowserMode,
) {
    val landscape = mode.landscape
    val category = mode.category
    val searching = query.isNotBlank()
    val visible = remember(entries, prefs, filter, query, category) {
        LibraryQuery.visible(entries, prefs, filter, query, category)
    }
    // ND15: solo juegos que se pueden continuar. `canResume` lee estado (huellas reanudables): derivedStateOf lo sigue.
    val rail by remember(entries, prefs, artworkFingerprints, actions) {
        derivedStateOf {
            LibraryQuery.continueRail(entries, prefs, isResumable = actions.canResume, hasArtwork = { it in artworkFingerprints })
        }
    }
    val showRail = filter == LibraryFilter.ALL && category == LibraryCategory.All && rail.isNotEmpty()
    // Al cerrar una búsqueda la cuadrícula vuelve a donde estaba.
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(scanning) { if (!scanning) pulled = false }
    val resetFilters = {
        onFilterChange(LibraryFilter.ALL)
        mode.onCategoryChange(LibraryCategory.All)
    }
    val title = sectionTitle(filter, category)
    Column(Modifier.fillMaxSize()) {
        if (scanning) ScanProgress(done, total)
        if (!landscape) {
            SearchField(query, onQueryChange)
            FilterRow(filter, onFilterChange)
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val fontScale = LocalDensity.current.fontScale
            // En vertical la barra no se pliega: el alto de esta caja ya es estable.
            val viewport = if (landscape) mode.viewportHeightDp else maxHeight.value
            val metrics = remember(maxWidth, viewport, fontScale, landscape) {
                railMetricsFor(maxWidth.value, viewport, fontScale, landscape)
            }
            PullToRefreshBox(
                isRefreshing = pulled,
                onRefresh = {
                    pulled = true
                    onRescan()
                },
                modifier = Modifier.fillMaxSize().onGloballyPositioned {
                    mode.tools.viewportTop = it.boundsInWindow().top.roundToInt()
                },
            ) {
                val bottomPadding = if (landscape) ToolbarClearance else 16.dp
                val railContent: @Composable () -> Unit = {
                    ContinueRail(
                        entries = rail,
                        prefs = prefs,
                        onOpenDetails = actions.onOpenDetails,
                        onContinue = actions.onPlay ?: actions.onOpenDetails,
                        metrics = metrics,
                    )
                }
                val landscapeHeader: (@Composable (GameMenuController) -> Unit)? = if (showRail) {
                    { _ -> railContent() }
                } else {
                    null
                }
                when {
                    visible.isEmpty() -> NoResults(
                        query = query,
                        filter = filter,
                        category = category,
                        allHidden = filter == LibraryFilter.ALL && category == LibraryCategory.All && !searching,
                        onSearchEverywhere = resetFilters,
                        onFilterChange = onFilterChange,
                    )
                    searching -> GameCollection(
                        entries = visible,
                        prefs = prefs,
                        layout = LibraryLayout.LIST,
                        actions = actions,
                        header = { ResultsCount(visible.size) },
                        bottomPadding = bottomPadding,
                    )
                    landscape -> GameCollection(
                        entries = visible,
                        prefs = prefs,
                        layout = prefs.layout,
                        actions = actions,
                        // En horizontal el título de sección se queda fijo arriba al desplazar (el carril no).
                        header = landscapeHeader,
                        pinnedHeader = { PinnedSectionHeader(title, folderName, mode.tools) },
                        gridState = gridState,
                        listState = listState,
                        bottomPadding = bottomPadding,
                    )
                    else -> GameCollection(
                        entries = visible,
                        prefs = prefs,
                        layout = prefs.layout,
                        actions = actions,
                        header = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (showRail) railContent()
                                SectionHeader(title = title, folderName = folderName)
                            }
                        },
                        gridState = gridState,
                        listState = listState,
                        bottomPadding = bottomPadding,
                    )
                }
            }
        }
    }
}

/** Aire bajo la última fila en horizontal para que la barra flotante no la tape (barra 56 dp + márgenes). */
private val ToolbarClearance = 88.dp

/** «Todos los juegos», el filtro, la categoría o «Categoría · filtro» (N3b). */
@Composable
private fun sectionTitle(filter: LibraryFilter, category: LibraryCategory): String {
    val filterTitle = if (filter == LibraryFilter.ALL) null else filter.title
    val categoryTitle = if (category == LibraryCategory.All) null else categoryTitle(category)
    return when {
        categoryTitle != null && filterTitle != null -> stringResource(R.string.n3_section_with_filter, categoryTitle, filterTitle)
        categoryTitle != null -> categoryTitle
        filterTitle != null -> filterTitle
        else -> stringResource(R.string.library_all_games)
    }
}

/**
 * Título de sección fijado (horizontal): fondo opaco de todo el ancho, para que las tarjetas pasen por debajo. Anota
 * dónde está (y dónde acaba su texto) para que los paneles de la barra flotante no lo tapen.
 */
@Composable
private fun PinnedSectionHeader(title: String, folderName: String?, tools: LibraryToolsState) {
    DisposableEffect(tools) { onDispose { tools.forgetHeader() } }
    Box(
        Modifier
            .bleedHorizontally(GRID_MARGIN_DP.dp)
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                tools.headerTop = bounds.top.roundToInt()
                tools.headerBottom = bounds.bottom.roundToInt()
            }
            .padding(horizontal = GRID_MARGIN_DP.dp)
            .heightIn(min = PinnedHeaderMinHeight)
            .testTag("library-pinned-header"),
        contentAlignment = Alignment.CenterStart,
    ) {
        SectionHeader(
            title = title,
            folderName = folderName,
            onTitlePlaced = { tools.titleRight = it },
        )
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
private fun SectionHeader(title: String, folderName: String?, onTitlePlaced: ((Int) -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (onTitlePlaced != null) {
                        Modifier.onGloballyPositioned { onTitlePlaced(it.boundsInWindow().right.roundToInt()) }
                    } else {
                        Modifier
                    },
                )
                .testTag("library-section-title")
                .semantics { heading() },
        )
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
                    // N3: a su medida (hasta 160 dp), así el icono de carpeta queda junto al nombre.
                    modifier = Modifier.widthIn(max = 160.dp),
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
    category: LibraryCategory,
    allHidden: Boolean,
    onSearchEverywhere: () -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
) {
    val narrowed = filter != LibraryFilter.ALL || category != LibraryCategory.All
    when {
        query.isNotBlank() && !narrowed -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.library_no_results_title, query.trim()),
            message = stringResource(R.string.library_no_results_all),
        )
        query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.library_no_results_title, query.trim()),
            message = if (category != LibraryCategory.All) {
                stringResource(R.string.n3_no_results_category, categoryTitle(category))
            } else {
                stringResource(R.string.library_no_results_filter, filter.title)
            },
            actions = {
                Button(onClick = onSearchEverywhere) {
                    Text(stringResource(R.string.library_search_all))
                }
            },
        )
        allHidden -> EmptyState(
            icon = Icons.Outlined.VisibilityOff,
            title = stringResource(R.string.library_all_hidden_title),
            message = stringResource(R.string.library_all_hidden_message),
        )
        category != LibraryCategory.All -> EmptyState(
            icon = Icons.Outlined.FolderOff,
            title = stringResource(R.string.n3_no_games_category_title, sectionTitle(filter, category)),
            message = stringResource(R.string.n3_no_games_category_message),
            actions = {
                Button(onClick = onSearchEverywhere) { Text(stringResource(R.string.n3_show_all_categories)) }
            },
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

/**
 * «Más opciones»: vista, orden, categoría (si hay carpetas, N3b), volver a escanear y cambiar de carpeta. En horizontal
 * ([viewOptions] = `false`) vista, orden y categoría están en la barra flotante y aquí quedan escanear y carpeta.
 */
@Composable
private fun MoreMenu(
    prefs: LibraryPreferencesData,
    onLayoutChange: (LibraryLayout) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onRescan: () -> Unit,
    onChooseFolder: () -> Unit,
    viewOptions: Boolean,
    categories: List<CategoryOption> = emptyList(),
    category: LibraryCategory = LibraryCategory.All,
    onCategoryChange: (LibraryCategory) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("view-menu")) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (viewOptions) {
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
                if (LibraryCategory.hasFolders(categories)) {
                    MenuHeader(stringResource(R.string.n3_menu_category))
                    MenuChoice(
                        stringResource(R.string.n3_category_all),
                        selected = category == LibraryCategory.All,
                        modifier = Modifier.testTag("menu-category-all"),
                    ) {
                        onCategoryChange(LibraryCategory.All)
                        expanded = false
                    }
                    categories.forEach { option ->
                        MenuChoice(
                            stringResource(R.string.n3_category_option, categoryTitle(option.category), option.count),
                            selected = category == option.category,
                            modifier = Modifier.testTag("menu-category-${categoryTag(option.category)}"),
                        ) {
                            onCategoryChange(option.category)
                            expanded = false
                        }
                    }
                    HorizontalDivider()
                }
            }
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
private fun MenuChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.library_selected)) }
        } else {
            { Box(Modifier.width(24.dp)) }
        },
    )
}
