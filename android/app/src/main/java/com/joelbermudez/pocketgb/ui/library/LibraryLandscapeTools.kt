package com.joelbermudez.pocketgb.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.CategoryOption
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibrarySort
import kotlin.math.roundToInt

/** Paneles de las herramientas de la biblioteca en horizontal (N3b). */
enum class LibraryPanel(val tag: String) {
    FILTERS("filters"),
    CATEGORIES("categories"),
    VIEW("view"),
}

/** Desde dónde se abrió un panel: los iconos de la barra superior (en reposo) o la barra flotante (al desplazar). */
enum class PanelSource { BAR, FLOATING }

/**
 * Solo el catálogo de capturas y las pruebas: panel o búsqueda abiertos al entrar (en la app, nada). [focusSearch] =
 * `false` deja la búsqueda abierta sin teclado. [scrolled] arranca con el carril fuera y la barra superior plegada en la
 * fracción [collapse] (estado «al desplazar», con la barra flotante); entonces [panel] se abre desde la barra flotante.
 */
data class LibraryToolsPreset(
    val panel: LibraryPanel? = null,
    val search: Boolean = false,
    val focusSearch: Boolean = true,
    val scrolled: Boolean = false,
    val collapse: Float = 1f,
)

val LocalLibraryToolsPreset = staticCompositionLocalOf { LibraryToolsPreset() }

/** Separación entre una barra y su panel, y entre el panel y el título de sección. */
private val PanelGap = 8.dp

/** Alto mínimo de un panel (con más opciones se desplaza dentro). */
internal val PanelMinHeight = 112.dp

/** Ancho máximo de un panel: caben los cuatro filtros en una fila. */
private val PanelMaxWidth = 480.dp

/** Alto mínimo del título de sección fijado (crece con la fuente: se mide). */
internal val PinnedHeaderMinHeight = 40.dp

/**
 * Estado de las herramientas en horizontal: qué panel está abierto y desde dónde, si la búsqueda está abierta (sobreviven
 * al girar) y las medidas que necesitan los paneles para no tapar el título de sección (lista y encabezado, en px de
 * ventana).
 */
@Stable
class LibraryToolsState internal constructor(
    private val searchState: MutableState<Boolean>,
    private val panelState: MutableState<LibraryPanel?>,
    initialSource: PanelSource = PanelSource.BAR,
) {
    var searchOpen: Boolean
        get() = searchState.value
        set(value) { searchState.value = value }

    var panel: LibraryPanel?
        get() = panelState.value
        set(value) { panelState.value = value }

    var panelSource by mutableStateOf(initialSource)

    /** Bordes superior e inferior (px, ventana) de la lista. */
    var viewportTop by mutableIntStateOf(0)
    var viewportBottom by mutableIntStateOf(0)

    /** Bordes (px, ventana) del título de sección mientras está en pantalla; `-1` si no lo está. */
    var headerTop by mutableIntStateOf(-1)
    var headerBottom by mutableIntStateOf(-1)

    /** Hay carril «Continuar jugando» delante del título de sección (lo anota la lista). */
    var railShown by mutableStateOf(false)

    /** El título de sección si está en pantalla. */
    val header: HeaderBounds?
        get() = if (headerTop < 0 || headerBottom <= headerTop) null else HeaderBounds(headerTop, headerBottom)

    fun forgetHeader() {
        headerTop = -1
        headerBottom = -1
    }

    /** Abre [target] desde [source]; si ya estaba abierto desde ahí, lo cierra. */
    fun toggle(target: LibraryPanel, source: PanelSource = PanelSource.BAR) {
        if (panel == target && panelSource == source) {
            panel = null
        } else {
            panelSource = source
            panel = target
        }
    }
}

@Composable
internal fun rememberLibraryToolsState(): LibraryToolsState {
    val preset = LocalLibraryToolsPreset.current
    val search = rememberSaveable { mutableStateOf(preset.search) }
    val panel = rememberSaveable { mutableStateOf(preset.panel) }
    return remember(search, panel) {
        LibraryToolsState(search, panel, if (preset.scrolled) PanelSource.FLOATING else PanelSource.BAR)
    }
}

/** Callbacks y valores que comparten los iconos de la barra superior y la barra flotante. */
internal class LibraryToolActions(
    val filter: LibraryFilter,
    val category: LibraryCategory,
    val categories: List<CategoryOption>,
    val layout: LibraryLayout,
    val sort: LibrarySort,
    val onSearch: () -> Unit,
    val onFilterChange: (LibraryFilter) -> Unit,
    val onCategoryChange: (LibraryCategory) -> Unit,
    val onLayoutChange: (LibraryLayout) -> Unit,
    val onSortChange: (LibrarySort) -> Unit,
)

/** Buscar, Filtros, Categorías y Vista/Orden; [tagPrefix] distingue los de la barra superior de los de la flotante. */
@Composable
private fun ToolButtons(tools: LibraryToolsState, actions: LibraryToolActions, source: PanelSource, tagPrefix: String, onOpen: (LibraryPanel) -> Unit) {
    val open = tools.panel.takeIf { tools.panelSource == source }
    ToolButton(
        icon = Icons.Filled.Search,
        label = stringResource(R.string.n3_tools_search),
        highlighted = false,
        state = null,
        tag = "$tagPrefix-search",
    ) {
        tools.panel = null
        actions.onSearch()
    }
    ToolButton(
        icon = if (actions.filter != LibraryFilter.ALL) Icons.Filled.FilterAlt else Icons.Outlined.FilterAlt,
        label = stringResource(R.string.n3_tools_filters),
        highlighted = actions.filter != LibraryFilter.ALL || open == LibraryPanel.FILTERS,
        state = stringResource(R.string.n3_tools_filter_state, actions.filter.title),
        tag = "$tagPrefix-filters",
    ) { onOpen(LibraryPanel.FILTERS) }
    ToolButton(
        icon = if (actions.category != LibraryCategory.All) Icons.Filled.Folder else Icons.Outlined.Folder,
        label = stringResource(R.string.n3_tools_categories),
        highlighted = actions.category != LibraryCategory.All || open == LibraryPanel.CATEGORIES,
        state = stringResource(R.string.n3_tools_category_state, categoryTitle(actions.category)),
        tag = "$tagPrefix-categories",
    ) { onOpen(LibraryPanel.CATEGORIES) }
    ToolButton(
        icon = if (actions.layout == LibraryLayout.GRID) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
        label = stringResource(R.string.n3_tools_view),
        highlighted = open == LibraryPanel.VIEW,
        state = stringResource(R.string.n3_tools_view_state, actions.layout.title, actions.sort.title),
        tag = "$tagPrefix-view",
    ) { onOpen(LibraryPanel.VIEW) }
}

/**
 * En reposo (horizontal): las cuatro herramientas como iconos de la barra superior (H1). Su panel cuelga **hacia abajo**
 * bajo la barra, sin tapar el título de sección ([PanelPlacement.below]). Al ir arriba, TalkBack las lee antes que la
 * lista (H4).
 */
@Composable
internal fun LibraryBarActions(tools: LibraryToolsState, actions: LibraryToolActions) {
    val density = LocalDensity.current
    var anchorBottom by remember { mutableIntStateOf(0) }
    Box(Modifier.onGloballyPositioned { anchorBottom = it.boundsInWindow().bottom.roundToInt() }) {
        Row(Modifier.testTag("library-bar-tools")) {
            ToolButtons(tools, actions, PanelSource.BAR, "bar") { tools.toggle(it, PanelSource.BAR) }
        }
        val panel = tools.panel.takeIf { tools.panelSource == PanelSource.BAR }
        if (panel != null) {
            val gap = with(density) { PanelGap.roundToPx() }
            val min = with(density) { PanelMinHeight.roundToPx() }
            val span = PanelPlacement.below(anchorBottom, tools.viewportBottom, tools.header, gap, min)
            val provider = remember(span.top, gap) { BelowPositionProvider(span.top, gap) }
            PanelPopup(tools, provider, panel, span.maxHeight, actions)
        }
    }
}

/**
 * Barra flotante de la biblioteca en horizontal (N3b): solo aparece al desplazar ([showFloatingToolbar]), en una pastilla
 * con componentes estables de Material 3 (sin `HorizontalFloatingToolbar`, que solo existe en 1.5.0-alpha). Cada botón
 * abre su panel **hacia arriba**, hasta debajo del título de sección fijado; si no cabe el mínimo (fuente grande con la
 * barra superior a medio plegar), [onNeedRoom] pliega antes la barra superior (H2). Va la primera en el orden de TalkBack
 * (H4).
 */
@Composable
internal fun LibraryToolbar(
    tools: LibraryToolsState,
    actions: LibraryToolActions,
    onNeedRoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var toolbarTop by remember { mutableIntStateOf(0) }
    val gap = with(density) { PanelGap.roundToPx() }
    val min = with(density) { PanelMinHeight.roundToPx() }
    Box(modifier.onGloballyPositioned { toolbarTop = it.boundsInWindow().top.roundToInt() }) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.testTag("library-tools").semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            },
        ) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolButtons(tools, actions, PanelSource.FLOATING, "tools") { target ->
                    val available = PanelPlacement.above(toolbarTop, tools.viewportTop, tools.header, gap)
                    if (PanelPlacement.needsRoom(available, min)) onNeedRoom()
                    tools.toggle(target, PanelSource.FLOATING)
                }
            }
        }
        val panel = tools.panel.takeIf { tools.panelSource == PanelSource.FLOATING }
        if (panel != null) {
            val available = PanelPlacement.above(toolbarTop, tools.viewportTop, tools.header, gap)
            val provider = remember(gap) { AbovePositionProvider(gap) }
            PanelPopup(tools, provider, panel, maxOf(available, min), actions)
        }
    }
}

/** El panel en su ventana emergente; elegir una opción lo cierra y la aplica. Alto en px (la ventana tiene su densidad). */
@Composable
private fun PanelPopup(
    tools: LibraryToolsState,
    provider: PopupPositionProvider,
    panel: LibraryPanel,
    maxHeightPx: Int,
    actions: LibraryToolActions,
) {
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = { tools.panel = null },
        properties = PopupProperties(focusable = true),
    ) {
        LibraryPanelContent(
            panel = panel,
            maxHeightPx = maxHeightPx,
            filter = actions.filter,
            category = actions.category,
            categories = actions.categories,
            layout = actions.layout,
            sort = actions.sort,
            onFilterChange = {
                tools.panel = null
                actions.onFilterChange(it)
            },
            onCategoryChange = {
                tools.panel = null
                actions.onCategoryChange(it)
            },
            onLayoutChange = {
                tools.panel = null
                actions.onLayoutChange(it)
            },
            onSortChange = {
                tools.panel = null
                actions.onSortChange(it)
            },
        )
    }
}

/** Coloca el panel en [topPx] (px de ventana), con su borde derecho alineado con el del ancla y dentro de la ventana. */
private class BelowPositionProvider(private val topPx: Int, private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val wanted = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val maxX = (windowSize.width - popupContentSize.width - gapPx).coerceAtLeast(gapPx)
        return IntOffset(wanted.coerceIn(gapPx, maxX), topPx)
    }
}

/** Coloca el panel encima de la barra, con su borde derecho alineado con el de la barra y dentro de la ventana. */
private class AbovePositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val wanted = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val maxX = (windowSize.width - popupContentSize.width - gapPx).coerceAtLeast(gapPx)
        val y = anchorBounds.top - gapPx - popupContentSize.height
        return IntOffset(wanted.coerceIn(gapPx, maxX), y.coerceAtLeast(gapPx))
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    highlighted: Boolean,
    state: String?,
    tag: String,
    onClick: () -> Unit,
) {
    // Un solo IconButton con colores según el estado (H5): el nodo de TalkBack es el mismo al abrir o cerrar el panel.
    IconButton(
        onClick = onClick,
        modifier = Modifier.testTag(tag).semantics { if (state != null) stateDescription = state },
        colors = if (highlighted) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) { Icon(icon, contentDescription = label) }
}

@Composable
internal fun categoryTitle(category: LibraryCategory): String = when (category) {
    LibraryCategory.All -> stringResource(R.string.n3_category_all)
    LibraryCategory.Uncategorized -> stringResource(R.string.n3_category_root)
    is LibraryCategory.Folder -> category.name
}

internal fun categoryTag(category: LibraryCategory): String = when (category) {
    LibraryCategory.All -> "all"
    LibraryCategory.Uncategorized -> "root"
    is LibraryCategory.Folder -> "folder-${category.name}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryPanelContent(
    panel: LibraryPanel,
    maxHeightPx: Int,
    filter: LibraryFilter,
    category: LibraryCategory,
    categories: List<CategoryOption>,
    layout: LibraryLayout,
    sort: LibrarySort,
    onFilterChange: (LibraryFilter) -> Unit,
    onCategoryChange: (LibraryCategory) -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
) {
    val title = when (panel) {
        LibraryPanel.FILTERS -> stringResource(R.string.n3_tools_filters)
        LibraryPanel.CATEGORIES -> stringResource(R.string.n3_tools_categories)
        LibraryPanel.VIEW -> stringResource(R.string.n3_tools_view)
    }
    val maxHeight = with(LocalDensity.current) { maxHeightPx.toDp() }.coerceAtLeast(PanelMinHeight)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .widthIn(min = 220.dp, max = PanelMaxWidth)
            .heightIn(max = maxHeight)
            .testTag("library-panel-${panel.tag}")
            .semantics { paneTitle = title },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (panel) {
                LibraryPanel.FILTERS -> {
                    PanelTitle(title)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibraryFilter.entries.forEach { option ->
                            OptionChip(option.title, filter == option, "panel-filter-${option.name}") { onFilterChange(option) }
                        }
                    }
                }
                LibraryPanel.CATEGORIES -> {
                    PanelTitle(title)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionChip(
                            stringResource(R.string.n3_category_all),
                            category == LibraryCategory.All,
                            "panel-category-all",
                        ) { onCategoryChange(LibraryCategory.All) }
                        categories.forEach { option ->
                            OptionChip(
                                stringResource(R.string.n3_category_option, categoryTitle(option.category), option.count),
                                category == option.category,
                                "panel-category-${categoryTag(option.category)}",
                            ) { onCategoryChange(option.category) }
                        }
                    }
                    if (!LibraryCategory.hasFolders(categories)) {
                        Text(
                            stringResource(R.string.n3_panel_categories_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
                LibraryPanel.VIEW -> {
                    // Dos filas con su etiqueta delante: caben en el poco alto del horizontal.
                    LabeledChips(stringResource(R.string.n3_panel_view)) {
                        LibraryLayout.entries.forEach { option ->
                            OptionChip(option.title, layout == option, "panel-layout-${option.name}") { onLayoutChange(option) }
                        }
                    }
                    LabeledChips(stringResource(R.string.n3_panel_sort)) {
                        LibrarySort.entries.forEach { option ->
                            OptionChip(option.title, sort == option, "panel-sort-${option.name}") { onSortChange(option) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabeledChips(label: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OptionChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier.testTag(tag),
    )
}

/**
 * Búsqueda en horizontal (N3b): el campo ocupa la barra superior mientras se busca. Atrás o la flecha la cierran,
 * vacían la búsqueda y vuelven la barra y la lista como estaban.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LandscapeSearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val requestFocus = LocalLibraryToolsPreset.current.focusSearch
    LaunchedEffect(Unit) { if (requestFocus) focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose, modifier = Modifier.testTag("library-search-close")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.n3_search_close))
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.n3_search_placeholder)) },
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
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("library-landscape-search"),
            )
        },
    )
}
