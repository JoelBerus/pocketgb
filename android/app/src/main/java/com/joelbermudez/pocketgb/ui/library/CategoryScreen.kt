package com.joelbermudez.pocketgb.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.CategoryPaths
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryTree
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.visible
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.ui.components.RenameGameHost
import com.joelbermudez.pocketgb.ui.components.rememberArtworkStore
import com.joelbermudez.pocketgb.ui.details.GameSettingsHost

/**
 * N4 · pantalla de una categoría (Navigation 3, restaurable): migas («Biblioteca › Pokémon › 2ª generación»),
 * subcategorías con su número de juegos y los juegos de la carpeta y de sus subcarpetas, en cuadrícula o lista (cada
 * categoría recuerda la suya). La carpeta que cuenta es la que se ve: la categoría virtual si el juego se movió en la app.
 */
@Composable
fun CategoryScreen(
    viewModel: LibraryViewModel,
    category: LibraryCategory,
    onBack: () -> Unit,
    onOpenCategory: (LibraryCategory) -> Unit,
    /** Una miga: la carpeta de un nivel superior, o `null` para volver a la biblioteca. */
    onOpenCrumb: (LibraryCategory?) -> Unit,
    onOpenDetails: (String) -> Unit,
    onPlay: (RomEntry) -> Unit,
    gameplaySettings: GameplaySettingsRepository? = null,
    onPlayFromStart: ((RomEntry) -> Unit)? = null,
    resumable: Set<String> = emptySet(),
    onOpenSaves: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    var settingsFor by remember { mutableStateOf<RomEntry?>(null) }
    var renameFor by remember { mutableStateOf<RomEntry?>(null) }
    val artwork = rememberArtworkStore()
    LifecycleResumeEffect(artwork) {
        artwork.refresh()
        onPauseOrDispose {}
    }
    val canResume by rememberUpdatedState { entry: RomEntry ->
        entry.isPlayable && prefs.fingerprints[entry.id]?.let { it in resumable } == true
    }
    val actions = remember(viewModel, onOpenDetails, onPlay, onPlayFromStart) {
        GameActions(
            onOpenDetails = { onOpenDetails(it.id) },
            onToggleFavorite = viewModel::toggleFavorite,
            onHide = viewModel::hide,
            onPlay = onPlay,
            onGameSettings = { settingsFor = it },
            onPlayFromStart = onPlayFromStart,
            onRename = { renameFor = it },
            canResume = { entry -> canResume(entry) },
        )
    }
    CategoryContent(
        state = state,
        prefs = prefs,
        category = category,
        actions = actions,
        onBack = onBack,
        onOpenCategory = onOpenCategory,
        onOpenCrumb = onOpenCrumb,
        onLayoutChange = { viewModel.setCategoryLayout(category, it) },
    )
    GameSettingsHost(
        entry = settingsFor,
        library = viewModel,
        repository = gameplaySettings ?: GameplaySettingsRepository.shared(LocalContext.current),
        onDismiss = { settingsFor = null },
        onOpenSaves = onOpenSaves,
    )
    RenameGameHost(entry = renameFor, prefs = prefs, onSetAlias = viewModel::setAlias, onDismiss = { renameFor = null })
}

/** La pantalla de categoría sin ViewModel (catálogo y pruebas). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryContent(
    state: LibraryState,
    prefs: LibraryPreferencesData,
    category: LibraryCategory,
    actions: GameActions,
    onBack: () -> Unit,
    onOpenCategory: (LibraryCategory) -> Unit,
    onOpenCrumb: (LibraryCategory?) -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = when (state) {
        is LibraryState.Ready -> state.entries
        is LibraryState.Scanning -> state.previous
        else -> emptyList()
    }
    val all = remember(entries, prefs) { LibraryQuery.visible(entries, prefs, LibraryFilter.ALL, "") }
    val games = remember(entries, prefs, category) { LibraryQuery.visible(entries, prefs, LibraryFilter.ALL, "", category) }
    val subcategories = remember(all, category) { LibraryTree.subcategories(all, category.path) }
    val layout = prefs.layoutFor(category)
    val name = categoryTitle(category)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = isLandscapeLibrary(maxWidth.value, maxHeight.value)
        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
        Scaffold(
            modifier = (if (landscape) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier).testTag("category-screen"),
            topBar = {
                TopAppBar(
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("category-title")) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("category-back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.details_back))
                        }
                    },
                    actions = { LayoutToggle(layout, onLayoutChange) },
                    scrollBehavior = if (landscape) scrollBehavior else null,
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when {
                    state is LibraryState.Loading || state is LibraryState.Scanning && entries.isEmpty() -> ScanningPane()
                    games.isEmpty() && subcategories.isEmpty() -> Column {
                        Breadcrumbs(category, onOpenCrumb, Modifier.padding(horizontal = GRID_MARGIN_DP.dp, vertical = 4.dp))
                        EmptyState(
                            icon = Icons.Outlined.FolderOff,
                            title = stringResource(R.string.n4_category_empty_title, name),
                            message = stringResource(R.string.n4_category_empty_message),
                            actions = {
                                Button(onClick = { onOpenCrumb(null) }, modifier = Modifier.testTag("category-back-home")) {
                                    Text(stringResource(R.string.n4_category_back_home))
                                }
                            },
                        )
                    }
                    else -> GameCollection(
                        entries = games,
                        prefs = prefs,
                        layout = layout,
                        actions = actions,
                        leadingItems = buildList {
                            add(CollectionItem("breadcrumbs") { Breadcrumbs(category, onOpenCrumb) })
                            if (subcategories.isNotEmpty()) {
                                add(CollectionItem("subcategories") { Subcategories(subcategories.map { it.category to it.count }, onOpenCategory) })
                            }
                            add(CollectionItem("games-header") { GamesHeader(games.size, category) })
                        },
                    )
                }
            }
        }
    }
}

/** Cuadrícula ↔ lista para esta categoría: el icono es el de la vista a la que cambia. */
@Composable
private fun LayoutToggle(layout: LibraryLayout, onLayoutChange: (LibraryLayout) -> Unit) {
    val next = if (layout == LibraryLayout.GRID) LibraryLayout.LIST else LibraryLayout.GRID
    val description = stringResource(if (next == LibraryLayout.LIST) R.string.n4_layout_show_list else R.string.n4_layout_show_grid)
    val state = stringResource(R.string.n4_layout_state, layout.title)
    IconButton(
        onClick = { onLayoutChange(next) },
        modifier = Modifier.testTag("category-layout-toggle").semantics { stateDescription = state },
    ) {
        Icon(
            if (next == LibraryLayout.LIST) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
            contentDescription = description,
        )
    }
}

/**
 * Migas: «Biblioteca › Pokémon › 2ª generación». Cada nivel superior se puede tocar (vuelve a esa pantalla); el actual
 * va en negrita. TalkBack lee primero la ruta entera («Estás en: …»).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Breadcrumbs(category: LibraryCategory, onOpenCrumb: (LibraryCategory?) -> Unit, modifier: Modifier = Modifier) {
    val root = stringResource(R.string.n4_breadcrumb_root)
    val names = if (category is LibraryCategory.Folder) category.path else listOf(categoryTitle(category))
    val full = CategoryPaths.display(listOf(root) + names)
    val description = stringResource(R.string.n4_breadcrumbs_description, full)
    FlowRow(
        modifier.fillMaxWidth().testTag("category-breadcrumbs").semantics { contentDescription = description },
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(root, "crumb-root") { onOpenCrumb(null) }
        val ancestors = if (category is LibraryCategory.Folder) LibraryTree.breadcrumbs(category.path).dropLast(1) else emptyList()
        ancestors.forEach { path ->
            Separator()
            Crumb(path.last(), "crumb-${path.joinToString("/")}") { onOpenCrumb(LibraryCategory.Folder(path)) }
        }
        Separator()
        Text(
            names.last(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp).testTag("crumb-current"),
        )
    }
}

@Composable
private fun Crumb(label: String, tag: String, onClick: () -> Unit) {
    val description = stringResource(R.string.n4_breadcrumb_go, label)
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Separator() {
    Text(
        "›",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

/** Subcategorías como chips con su número de juegos (todo su subárbol); cada una abre su pantalla. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Subcategories(options: List<Pair<LibraryCategory, Int>>, onOpenCategory: (LibraryCategory) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("category-subcategories")) {
        Text(
            stringResource(R.string.n4_subcategories),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (option, count) ->
                val countText = pluralStringResource(R.plurals.n4_games, count, count)
                CategoryChip(
                    stringResource(R.string.n3_category_option, categoryTitle(option), count),
                    stringResource(R.string.n4_subcategory_description, categoryTitle(option), countText),
                    "subcategory-${categoryTag(option)}",
                ) { onOpenCategory(option) }
            }
        }
    }
}

/** «Juegos · 5» con de dónde salen (la carpeta y sus subcarpetas, o la carpeta principal). */
@Composable
private fun GamesHeader(count: Int, category: LibraryCategory) {
    val countText = pluralStringResource(R.plurals.n4_games, count, count)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("category-games-header")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.n4_category_games), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(countText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(if (category is LibraryCategory.Folder) R.string.n4_category_games_subtree else R.string.n4_category_games_root),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
