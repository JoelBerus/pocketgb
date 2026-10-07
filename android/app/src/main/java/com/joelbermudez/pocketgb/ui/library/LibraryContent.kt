package com.joelbermudez.pocketgb.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.style.TextOverflow
import com.joelbermudez.pocketgb.ui.a11y.LocalReduceMotion
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.ArrowDropDown
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
import com.joelbermudez.pocketgb.library.HomeSections
import com.joelbermudez.pocketgb.library.LibraryHome
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.TagOption
import com.joelbermudez.pocketgb.library.Tags
import com.joelbermudez.pocketgb.library.tagOptions
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
 * N3b: si el espacio es más ancho que alto ([isLandscapeLibrary]) la búsqueda y los chips dejan la parte de arriba.
 * En reposo Buscar, Filtros, Categorías y Vista/Orden son iconos de la barra superior ([LibraryBarActions]) y sus paneles
 * cuelgan hacia abajo. Al desplazar, la barra superior se pliega, el título de sección se queda fijo y aparece a la
 * derecha la barra flotante ([LibraryToolbar]) con paneles hacia arriba; al subir vuelven los iconos de arriba y la
 * flotante se va. En vertical la estructura es la de siempre.
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
    /** N4: abre la pantalla de una categoría (estanterías, panel Categorías y menú «⋮»). */
    onOpenCategory: (LibraryCategory) -> Unit = {},
    /** N4: «Ver todo» de la fila de Favoritos. */
    onOpenFavorites: () -> Unit = {},
    /** N4: etiqueta elegida en los filtros (`null` = todas). */
    tag: String? = null,
    onTagChange: (String?) -> Unit = {},
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
    val tags = remember(shownEntries, prefs) { LibraryQuery.tagOptions(shownEntries, prefs) }
    val tools = rememberLibraryToolsState()
    val preset = LocalLibraryToolsPreset.current
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topBarState)
    // Las posiciones de la cuadrícula y la lista viven aquí: deciden si el título está fijado (barra flotante) y se
    // conservan al cerrar una búsqueda.
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = isLandscapeLibrary(maxWidth.value, maxHeight.value)
        // Alto visible de la lista con la barra superior desplegada (estable mientras la barra se pliega).
        val viewportHeight = (maxHeight - TopAppBarDefaults.TopAppBarExpandedHeight).value
        val searchVisible = landscape && showsGames && (tools.searchOpen || query.isNotEmpty())
        // H7: al cerrar la búsqueda la barra superior vuelve como estaba (plegada o no), no siempre desplegada.
        var offsetBeforeSearch by rememberSaveable { mutableFloatStateOf(0f) }
        val openSearch = {
            offsetBeforeSearch = topBarState.heightOffset
            tools.panel = null
            tools.searchOpen = true
        }
        val closeSearch = {
            onQueryChange("")
            tools.searchOpen = false
            topBarState.heightOffset = offsetBeforeSearch
        }
        BackHandler(enabled = searchVisible) { closeSearch() }
        // Al pasar de horizontal a vertical se cierra un panel abierto (solo en ese paso: el catálogo puede arrancar en
        // vertical antes de girar). En vertical, sin texto, la búsqueda horizontal queda cerrada (H7: si se borra en
        // vertical, al volver a horizontal no reaparece vacía).
        var wasLandscape by remember { mutableStateOf(landscape) }
        LaunchedEffect(landscape, query.isEmpty()) {
            if (wasLandscape && !landscape) tools.panel = null
            if (!landscape && query.isEmpty()) tools.searchOpen = false
            wasLandscape = landscape
        }
        // H1: la barra flotante solo al desplazar (barra superior plegada a la mitad o más y título de sección fijado).
        val collapsed by remember { derivedStateOf { topBarState.collapsedFraction } }
        val firstVisible by remember(prefs.layout) {
            derivedStateOf { if (prefs.layout == LibraryLayout.GRID) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex }
        }
        val floating = showsGames && showFloatingToolbar(
            landscape = landscape,
            searching = searchVisible,
            collapsedFraction = collapsed,
            titlePinned = isTitlePinned(tools.leadingItems, firstVisible),
        )
        // Si la barra flotante se va (al subir) con su panel abierto, el panel se cierra; solo en ese paso (el catálogo
        // abre un panel flotante antes de desplazar).
        var wasFloating by remember { mutableStateOf(false) }
        LaunchedEffect(floating) {
            if (wasFloating && !floating && tools.panelSource == PanelSource.FLOATING) tools.panel = null
            wasFloating = floating
        }
        // Catálogo y pruebas: arrancar «desplazado» (las filas del inicio fuera y la barra superior plegada en la fracción
        // pedida). Lo hace la lista (GameBrowser), que sabe cuántas filas del inicio hay delante del título.
        val presetScroll: (suspend (Int) -> Unit)? = if (preset.scrolled) {
            { target ->
                if (prefs.layout == LibraryLayout.GRID) gridState.scrollToItem(target) else listState.scrollToItem(target)
                withFrameNanos {}
                topBarState.heightOffset = topBarState.heightOffsetLimit * preset.collapse.coerceIn(0f, 1f)
            }
        } else {
            null
        }
        val toolActions = LibraryToolActions(
            filter = filter,
            categories = categories,
            layout = prefs.layout,
            sort = prefs.sort,
            onSearch = openSearch,
            onFilterChange = onFilterChange,
            onOpenCategory = onOpenCategory,
            onLayoutChange = onLayoutChange,
            onSortChange = onSortChange,
            tag = tag,
            tags = tags,
            onTagChange = onTagChange,
        )
        // H2: si al abrir un panel desde la barra flotante no cabe sin tapar el título, se pliega del todo la barra superior.
        val foldTopBar: () -> Unit = {
            scope.launch {
                val target = topBarState.heightOffsetLimit
                if (reduceMotion) {
                    topBarState.heightOffset = target
                } else {
                    animate(topBarState.heightOffset, target) { value, _ -> topBarState.heightOffset = value }
                }
            }
        }
        Scaffold(
            modifier = if (landscape) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
            topBar = {
                when {
                    searchVisible -> LandscapeSearchBar(query, onQueryChange, onClose = closeSearch)
                    landscape -> TopAppBar(
                        title = { Text(stringResource(R.string.library_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        actions = {
                            if (showsGames) {
                                LibraryBarActions(tools, toolActions)
                                MoreMenu(prefs, onLayoutChange, onSortChange, onRescan, onChooseFolder, viewOptions = false)
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        modifier = Modifier.testTag("library-top-bar"),
                    )
                    else -> TopAppBar(
                        title = { Text(stringResource(R.string.library_title)) },
                        actions = {
                            if (showsGames) {
                                MoreMenu(
                                    prefs, onLayoutChange, onSortChange, onRescan, onChooseFolder,
                                    viewOptions = true,
                                    categories = categories,
                                    onOpenCategory = onOpenCategory,
                                )
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                // Aparece y se va deslizándose desde abajo; con «reducir movimiento», sin animación.
                AnimatedVisibility(
                    visible = floating,
                    enter = if (reduceMotion) EnterTransition.None else slideInVertically { it } + fadeIn(),
                    exit = if (reduceMotion) ExitTransition.None else slideOutVertically { it } + fadeOut(),
                ) {
                    LibraryToolbar(tools = tools, actions = toolActions, onNeedRoom = foldTopBar)
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (preferencesReadOnly) PreferencesReadOnlyBanner()
            Box(Modifier.fillMaxSize()) {
                val browser = BrowserMode(
                    landscape = landscape,
                    tag = tag,
                    tags = tags,
                    onTagChange = onTagChange,
                    onOpenCategory = onOpenCategory,
                    onOpenFavorites = onOpenFavorites,
                    viewportHeightDp = viewportHeight,
                    tools = tools,
                    gridState = gridState,
                    listState = listState,
                    floatingToolbar = floating,
                    reduceMotion = reduceMotion,
                    presetScroll = presetScroll,
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

/** Lo que cambia la lista en horizontal (N3b), la etiqueta elegida y la navegación del inicio (N4). */
private class BrowserMode(
    val landscape: Boolean,
    val tag: String?,
    val tags: List<TagOption>,
    val onTagChange: (String?) -> Unit,
    val onOpenCategory: (LibraryCategory) -> Unit,
    val onOpenFavorites: () -> Unit,
    /** Alto visible de la lista con la barra superior desplegada: limita el alto del carril en horizontal. */
    val viewportHeightDp: Float,
    val tools: LibraryToolsState,
    val gridState: LazyGridState,
    val listState: LazyListState,
    /** La barra flotante está a la vista: la lista deja 88 dp de aire al final. */
    val floatingToolbar: Boolean,
    val reduceMotion: Boolean,
    /** Solo catálogo y pruebas: desplazar hasta el título de sección (índice) y plegar la barra superior. */
    val presetScroll: (suspend (Int) -> Unit)? = null,
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
    val tag = mode.tag
    val searching = query.isNotBlank()
    val visible = remember(entries, prefs, filter, query, tag) {
        LibraryQuery.visible(entries, prefs, filter, query, LibraryCategory.All, tag)
    }
    // ND15: solo juegos que se pueden continuar. `canResume` lee estado (huellas reanudables): derivedStateOf lo sigue.
    val rail by remember(entries, prefs, artworkFingerprints, actions) {
        derivedStateOf {
            LibraryQuery.continueRail(entries, prefs, isResumable = actions.canResume, hasArtwork = { it in artworkFingerprints })
        }
    }
    // N4: el inicio (carril, Favoritos y estanterías) solo sin búsqueda, filtro ni etiqueta.
    val home = !searching && filter == LibraryFilter.ALL && tag == null
    val showRail = home && rail.isNotEmpty()
    val sections = remember(entries, prefs, home) {
        if (home) LibraryHome.sections(entries, prefs) else HomeSections(emptyList(), emptyList())
    }
    // Al cerrar una búsqueda la cuadrícula vuelve a donde estaba (los estados viven en LibraryContent).
    val gridState = mode.gridState
    val listState = mode.listState
    val leadingCount = (if (showRail) 1 else 0) + (if (sections.favorites.isNotEmpty()) 1 else 0) + sections.shelves.size
    SideEffect { mode.tools.leadingItems = if (landscape) leadingCount else 0 }
    val presetScroll = mode.presetScroll
    if (presetScroll != null && landscape) {
        // Se repite al girar o si cambian las filas del inicio (el catálogo puede arrancar en vertical).
        LaunchedEffect(landscape, leadingCount) {
            withFrameNanos {}
            presetScroll(leadingCount.coerceAtLeast(1))
        }
    }
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(scanning) { if (!scanning) pulled = false }
    val resetFilters = {
        onFilterChange(LibraryFilter.ALL)
        mode.onTagChange(null)
    }
    val title = sectionTitle(filter, tag)
    Column(Modifier.fillMaxSize()) {
        if (scanning) ScanProgress(done, total)
        if (!landscape) {
            SearchField(query, onQueryChange)
            FilterRow(filter, onFilterChange, tag, mode.tags, mode.onTagChange)
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
                    val bounds = it.boundsInWindow()
                    mode.tools.viewportTop = bounds.top.roundToInt()
                    mode.tools.viewportBottom = bounds.bottom.roundToInt()
                },
            ) {
                // 88 dp de aire al final mientras se ve la barra flotante (cambia con suavidad: la lista no salta).
                val bottomPadding by animateDpAsState(
                    targetValue = if (landscape && mode.floatingToolbar) ToolbarClearance else 16.dp,
                    animationSpec = if (mode.reduceMotion) snap() else tween(),
                    label = "library-bottom-padding",
                )
                val railContent: @Composable () -> Unit = {
                    ContinueRail(
                        entries = rail,
                        prefs = prefs,
                        onOpenDetails = actions.onOpenDetails,
                        onContinue = actions.onPlay ?: actions.onOpenDetails,
                        metrics = metrics,
                    )
                }
                val homeItems = homeItems(sections, prefs, actions, metrics, mode, railContent.takeIf { showRail })
                when {
                    visible.isEmpty() -> NoResults(
                        query = query,
                        filter = filter,
                        allHidden = filter == LibraryFilter.ALL && tag == null && !searching,
                        onSearchEverywhere = resetFilters,
                        onFilterChange = onFilterChange,
                        tag = tag,
                        onClearTag = { mode.onTagChange(null) },
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
                        // En horizontal el título de sección se queda fijo arriba al desplazar (el inicio no).
                        leadingItems = homeItems,
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
                        leadingItems = homeItems + CollectionItem("section-title") { SectionHeader(title = title, folderName = folderName) },
                        gridState = gridState,
                        listState = listState,
                        bottomPadding = bottomPadding,
                    )
                }
            }
        }
    }
}

/**
 * N4 · filas del inicio antes de «Todos los juegos»: el carril «Continuar jugando» (si lo hay), la fila de Favoritos y
 * una estantería por categoría de primer nivel en el orden de Ajustes › Biblioteca › Inicio.
 */
@Composable
private fun homeItems(
    sections: HomeSections,
    prefs: LibraryPreferencesData,
    actions: GameActions,
    metrics: RailMetrics,
    mode: BrowserMode,
    rail: (@Composable () -> Unit)?,
): List<CollectionItem> {
    val favoritesTitle = stringResource(R.string.n4_favorites_row)
    val root = stringResource(R.string.n3_category_root)
    val favoritesCount = sections.favorites.size
    val favoritesDescription = stringResource(
        R.string.n4_see_all_favorites_description,
        pluralStringResource(R.plurals.n4_games, favoritesCount, favoritesCount),
    )
    val seeAllFormat = stringResource(R.string.n4_see_all_description)
    val resources = androidx.compose.ui.platform.LocalResources.current
    return buildList {
        if (rail != null) add(CollectionItem("rail") { rail() })
        if (sections.favorites.isNotEmpty()) {
            add(
                CollectionItem("favorites") { menu ->
                    ShelfRow(
                        title = favoritesTitle,
                        total = favoritesCount,
                        games = sections.favorites,
                        prefs = prefs,
                        actions = actions,
                        menu = menu,
                        metrics = metrics,
                        place = "favorites",
                        seeAllDescription = favoritesDescription,
                        onSeeAll = mode.onOpenFavorites,
                    )
                },
            )
        }
        sections.shelves.forEach { shelf ->
            val name = shelf.category.folderName ?: root
            val countText = resources.getQuantityString(R.plurals.n4_games, shelf.total, shelf.total)
            add(
                CollectionItem("shelf-${shelf.category.key}") { menu ->
                    ShelfRow(
                        title = name,
                        total = shelf.total,
                        games = shelf.games,
                        prefs = prefs,
                        actions = actions,
                        menu = menu,
                        metrics = metrics,
                        place = categoryTag(shelf.category),
                        seeAllDescription = String.format(seeAllFormat, name, countText),
                        onSeeAll = { mode.onOpenCategory(shelf.category) },
                        pinned = shelf.pinned,
                    )
                },
            )
        }
    }
}

/** Aire bajo la última fila en horizontal para que la barra flotante no la tape (barra 56 dp + márgenes). */
private val ToolbarClearance = 88.dp

/** «Todos los juegos», el filtro, la etiqueta (N4) o ambos (N3b; lógica en [sectionTitle] puro). */
@Composable
private fun sectionTitle(filter: LibraryFilter, tag: String?): String {
    val allGames = stringResource(R.string.library_all_games)
    val root = stringResource(R.string.n3_category_root)
    val format = stringResource(R.string.n3_section_with_filter)
    val tagFormat = stringResource(R.string.n4_section_tag)
    val labels = SectionLabels(allGames, root, tag = { String.format(tagFormat, it) }) { a, b -> String.format(format, a, b) }
    return sectionTitle(filter, LibraryCategory.All, labels, tag)
}

/**
 * Título de sección fijado (horizontal): fondo opaco de todo el ancho, para que las tarjetas pasen por debajo. Anota
 * dónde está el encabezado entero (título y carpeta, H6) para que los paneles no lo tapen.
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
        SectionHeader(title = title, folderName = folderName)
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
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f, fill = false)
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
private fun FilterRow(
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    tag: String? = null,
    tags: List<TagOption> = emptyList(),
    onTagChange: (String?) -> Unit = {},
) {
    val scroll = rememberScrollState()
    // N4: con una etiqueta elegida, su chip (el último) se ve sin desplazar la fila a mano.
    LaunchedEffect(tag != null) { if (tag != null) scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier.horizontalScroll(scroll).padding(horizontal = 16.dp),
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
        // N4: filtro por etiqueta (un chip con su menú), solo si algún juego tiene etiquetas.
        if (tags.isNotEmpty() || tag != null) TagFilterChip(tag, tags, onTagChange)
    }
}

/** N4 · vertical: «Etiqueta» abre la lista de etiquetas (con cuántos juegos la llevan); elegida, el chip la nombra. */
@Composable
private fun TagFilterChip(tag: String?, tags: List<TagOption>, onTagChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = tag != null,
            onClick = { open = true },
            label = { Text(if (tag != null) stringResource(R.string.n4_tag_filter_selected, tag) else stringResource(R.string.n4_tag_filter)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.testTag("filter-tag"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuChoice(stringResource(R.string.n4_tag_all), selected = tag == null, modifier = Modifier.testTag("menu-tag-all")) {
                onTagChange(null)
                open = false
            }
            tags.forEach { option ->
                MenuChoice(
                    stringResource(R.string.n4_tag_option, option.tag, option.count),
                    selected = tag != null && Tags.same(tag, option.tag),
                    modifier = Modifier.testTag("menu-tag-${option.tag}"),
                ) {
                    onTagChange(option.tag)
                    open = false
                }
            }
        }
    }
}

@Composable
private fun NoResults(
    query: String,
    filter: LibraryFilter,
    allHidden: Boolean,
    onSearchEverywhere: () -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    tag: String? = null,
    onClearTag: () -> Unit = {},
) {
    val narrowed = filter != LibraryFilter.ALL || tag != null
    when {
        query.isNotBlank() && !narrowed -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.library_no_results_title, query.trim()),
            message = stringResource(R.string.library_no_results_all),
        )
        query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.library_no_results_title, query.trim()),
            message = stringResource(
                R.string.library_no_results_filter,
                if (tag != null) sectionTitle(filter, tag) else filter.title,
            ),
            actions = {
                Button(onClick = onSearchEverywhere) {
                    Text(stringResource(R.string.library_search_all))
                }
            },
        )
        tag != null -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.n4_no_games_tag_title, tag),
            message = stringResource(R.string.n4_no_games_tag_message),
            actions = { Button(onClick = onClearTag, modifier = Modifier.testTag("library-clear-tag")) { Text(stringResource(R.string.n4_clear_tag)) } },
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

/**
 * «Más opciones»: vista, orden, categorías (si hay carpetas, N3b; N4: cada una abre su pantalla), volver a escanear y
 * cambiar de carpeta. En horizontal ([viewOptions] = `false`) vista, orden y categorías están en las herramientas y aquí
 * quedan escanear y carpeta.
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
    onOpenCategory: (LibraryCategory) -> Unit = {},
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
                    MenuHeader(stringResource(R.string.n4_menu_categories))
                    categories.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.n3_category_option, categoryTitle(option.category), option.count)) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onOpenCategory(option.category)
                            },
                            modifier = Modifier.testTag("menu-category-${categoryTag(option.category)}"),
                        )
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
