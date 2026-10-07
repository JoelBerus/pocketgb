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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/** Paneles de la barra flotante de la biblioteca en horizontal (N3b). */
enum class LibraryPanel(val tag: String) {
    FILTERS("filters"),
    CATEGORIES("categories"),
    VIEW("view"),
}

/**
 * Solo el catálogo de capturas: panel o búsqueda abiertos al entrar (en la app, nada). [focusSearch] = `false` deja la
 * búsqueda abierta sin teclado (para ver los resultados).
 */
data class LibraryToolsPreset(val panel: LibraryPanel? = null, val search: Boolean = false, val focusSearch: Boolean = true)

val LocalLibraryToolsPreset = staticCompositionLocalOf { LibraryToolsPreset() }

/** Separación entre la barra flotante y su panel, y entre el panel y el título de sección fijado. */
private val PanelGap = 8.dp

/** Alto mínimo de un panel aunque no quepa entero (se desplaza dentro). */
private val PanelMinHeight = 112.dp

/** Ancho máximo de un panel: caben los cuatro filtros en una fila. */
private val PanelMaxWidth = 480.dp

/** Alto mínimo del título de sección fijado (crece con la fuente: se mide). */
internal val PinnedHeaderMinHeight = 40.dp

/**
 * Estado de la barra flotante: qué panel está abierto, si la búsqueda está abierta (sobreviven al girar) y dónde
 * empieza la lista y cuánto mide el título fijado (para que un panel nunca tape el título de sección).
 */
@Stable
class LibraryToolsState internal constructor(
    private val searchState: MutableState<Boolean>,
    private val panelState: MutableState<LibraryPanel?>,
) {
    var searchOpen: Boolean
        get() = searchState.value
        set(value) { searchState.value = value }

    var panel: LibraryPanel?
        get() = panelState.value
        set(value) { panelState.value = value }

    /** Borde superior (px, ventana) de la lista: el título de sección fijado queda justo debajo. */
    var viewportTop by mutableIntStateOf(0)

    /** Bordes (px, ventana) del título de sección mientras está en pantalla; `-1` si no lo está. */
    var headerTop by mutableIntStateOf(-1)
    var headerBottom by mutableIntStateOf(-1)

    /** Borde derecho (px, ventana) del texto del título de sección, para saber si un panel lo taparía. */
    var titleRight by mutableIntStateOf(-1)

    /**
     * Hasta dónde puede subir un panel que acaba en [toolbarTop] y empieza como mucho en [panelLeft] (px, ventana): bajo
     * el título de sección si se ve por encima de la barra y el panel lo taparía; si no (por ejemplo, arriba del todo,
     * con el carril y el título aún por debajo), hasta la barra superior.
     */
    fun panelLimitTop(toolbarTop: Int, panelLeft: Int): Int {
        val headerShown = headerBottom > viewportTop && headerTop in 0 until toolbarTop
        return if (headerShown && titleRight > panelLeft) headerBottom else viewportTop
    }

    fun forgetHeader() {
        headerTop = -1
        headerBottom = -1
        titleRight = -1
    }

    fun toggle(target: LibraryPanel) {
        panel = if (panel == target) null else target
    }
}

@Composable
internal fun rememberLibraryToolsState(): LibraryToolsState {
    val preset = LocalLibraryToolsPreset.current
    val search = rememberSaveable { mutableStateOf(preset.search) }
    val panel = rememberSaveable { mutableStateOf(preset.panel) }
    return remember(search, panel) { LibraryToolsState(search, panel) }
}

/**
 * Barra flotante de la biblioteca en horizontal (N3b): Buscar, Filtros, Categorías y Vista/Orden en una pastilla con
 * componentes estables de Material 3 (sin `HorizontalFloatingToolbar`, que solo existe en 1.5.0-alpha). Cada botón abre
 * su panel **hacia arriba**, pegado a la barra, con un alto máximo que no llega al título de sección fijado; si las
 * opciones no caben, el panel se desplaza dentro. Tocar fuera o Atrás lo cierran.
 */
@Composable
internal fun LibraryToolbar(
    tools: LibraryToolsState,
    filter: LibraryFilter,
    category: LibraryCategory,
    categories: List<CategoryOption>,
    layout: LibraryLayout,
    sort: LibrarySort,
    onSearch: () -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onCategoryChange: (LibraryCategory) -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var toolbarTop by remember { mutableIntStateOf(0) }
    var toolbarRight by remember { mutableIntStateOf(0) }
    Box(
        modifier.onGloballyPositioned {
            val bounds = it.boundsInWindow()
            toolbarTop = bounds.top.roundToInt()
            toolbarRight = bounds.right.roundToInt()
        },
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.testTag("library-tools").semantics { isTraversalGroup = true },
        ) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolButton(
                    icon = Icons.Filled.Search,
                    label = stringResource(R.string.n3_tools_search),
                    highlighted = false,
                    state = null,
                    tag = "tools-search",
                ) {
                    tools.panel = null
                    onSearch()
                }
                ToolButton(
                    icon = if (filter != LibraryFilter.ALL) Icons.Filled.FilterAlt else Icons.Outlined.FilterAlt,
                    label = stringResource(R.string.n3_tools_filters),
                    highlighted = filter != LibraryFilter.ALL || tools.panel == LibraryPanel.FILTERS,
                    state = stringResource(R.string.n3_tools_filter_state, filter.title),
                    tag = "tools-filters",
                ) { tools.toggle(LibraryPanel.FILTERS) }
                ToolButton(
                    icon = if (category != LibraryCategory.All) Icons.Filled.Folder else Icons.Outlined.Folder,
                    label = stringResource(R.string.n3_tools_categories),
                    highlighted = category != LibraryCategory.All || tools.panel == LibraryPanel.CATEGORIES,
                    state = stringResource(R.string.n3_tools_category_state, categoryTitle(category)),
                    tag = "tools-categories",
                ) { tools.toggle(LibraryPanel.CATEGORIES) }
                ToolButton(
                    icon = if (layout == LibraryLayout.GRID) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
                    label = stringResource(R.string.n3_tools_view),
                    highlighted = tools.panel == LibraryPanel.VIEW,
                    state = stringResource(R.string.n3_tools_view_state, layout.title, sort.title),
                    tag = "tools-view",
                ) { tools.toggle(LibraryPanel.VIEW) }
            }
        }
        val panel = tools.panel
        if (panel != null) {
            val gap = with(density) { PanelGap.roundToPx() }
            val panelLeft = toolbarRight - with(density) { PanelMaxWidth.roundToPx() }
            // Del borde superior de la barra hasta debajo del título de sección (o de la barra superior si no se ve), en px:
            // el panel lo convierte con su propia densidad (la ventana emergente puede tener otra).
            val availablePx = toolbarTop - gap - (tools.panelLimitTop(toolbarTop, panelLeft) + gap)
            val provider = remember(gap) { AbovePositionProvider(gap) }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { tools.panel = null },
                properties = PopupProperties(focusable = true),
            ) {
                LibraryPanelContent(
                    panel = panel,
                    maxHeightPx = availablePx,
                    filter = filter,
                    category = category,
                    categories = categories,
                    layout = layout,
                    sort = sort,
                    onFilterChange = {
                        tools.panel = null
                        onFilterChange(it)
                    },
                    onCategoryChange = {
                        tools.panel = null
                        onCategoryChange(it)
                    },
                    onLayoutChange = {
                        tools.panel = null
                        onLayoutChange(it)
                    },
                    onSortChange = {
                        tools.panel = null
                        onSortChange(it)
                    },
                )
            }
        }
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
    val modifier = Modifier.testTag(tag).semantics { if (state != null) stateDescription = state }
    if (highlighted) {
        FilledTonalIconButton(onClick = onClick, modifier = modifier) { Icon(icon, contentDescription = label) }
    } else {
        IconButton(onClick = onClick, modifier = modifier) { Icon(icon, contentDescription = label) }
    }
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
