package com.joelbermudez.pocketgb.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.library.LibraryError
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibrarySort
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.ui.components.GameCard
import com.joelbermudez.pocketgb.ui.components.GameListItem
import com.joelbermudez.pocketgb.ui.components.HideGameDialog
import com.joelbermudez.pocketgb.ui.components.summary

/** Acciones sobre un juego, comunes a Biblioteca y Favoritos. */
class GameActions(
    val onOpenDetails: (RomEntry) -> Unit,
    val onToggleFavorite: (RomEntry) -> Unit,
    val onHide: (RomEntry) -> Unit,
    /** Abre el juego directamente ("Continuar jugando"); sin valor, abre su detalle. */
    val onPlay: ((RomEntry) -> Unit)? = null,
)

/**
 * Pantalla de Biblioteca sin ViewModel: recibe estado y callbacks, así que el catálogo
 * debug y las pruebas Compose la ejercitan con datos sintéticos.
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
) {
    val showsGames = state is LibraryState.Ready && state.entries.isNotEmpty() ||
        state is LibraryState.Scanning && state.previous.isNotEmpty()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Biblioteca") },
                actions = { if (showsGames) ViewMenu(prefs, onLayoutChange, onSortChange) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (state) {
                LibraryState.Loading -> ScanningPane(message = "Cargando biblioteca…")
                LibraryState.NoFolder -> EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = "Elige una carpeta con tus juegos",
                    message = "PocketGB busca archivos .gb y .gbc en la carpeta y sus subcarpetas directas. " +
                        "Nunca los copia ni los modifica.",
                    actions = { Button(onClick = onChooseFolder) { Text("Elegir carpeta") } },
                )
                is LibraryState.Scanning -> if (state.previous.isEmpty()) {
                    ScanningPane()
                } else {
                    GameBrowser(state.previous, prefs, query, filter, onQueryChange, onFilterChange, actions, scanning = true)
                }
                is LibraryState.Ready -> if (state.entries.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.SportsEsports,
                        title = "No hay juegos en esta carpeta",
                        message = "Solo se buscan archivos .gb y .gbc en la carpeta y un nivel de subcarpetas.",
                        actions = {
                            Button(onClick = onRescan) { Text("Volver a escanear") }
                            OutlinedButton(onClick = onChooseFolder) { Text("Elegir otra carpeta") }
                        },
                    )
                } else {
                    GameBrowser(state.entries, prefs, query, filter, onQueryChange, onFilterChange, actions, scanning = false)
                }
                is LibraryState.Failed -> LibraryErrorPane(state.error, onChooseFolder, onRescan)
            }
        }
    }
}

@Composable
internal fun ScanningPane(modifier: Modifier = Modifier, message: String = "Buscando juegos…") {
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
            title = "PocketGB perdió el acceso a la carpeta",
            message = "El sistema revocó el permiso. Tus juegos y partidas siguen donde estaban; " +
                "elige la carpeta otra vez para concederlo de nuevo.",
            actions = { Button(onClick = onChooseFolder) { Text("Volver a elegir") } },
        )
        LibraryError.FolderMissing -> EmptyState(
            icon = Icons.Outlined.FolderOff,
            title = "La carpeta ya no existe",
            message = "Se movió o se borró. Elige otra carpeta o reintenta si la restauraste.",
            actions = {
                Button(onClick = onChooseFolder) { Text("Elegir otra carpeta") }
                OutlinedButton(onClick = onRescan) { Text("Reintentar") }
            },
        )
        LibraryError.AccessNotKept -> EmptyState(
            icon = Icons.Outlined.ErrorOutline,
            title = "No se pudo conservar el acceso a la carpeta",
            message = "El sistema no permitió recordar la carpeta elegida. La carpeta anterior no se tocó; " +
                "elige otra vez o reintenta con la que ya tenías.",
            actions = {
                Button(onClick = onChooseFolder) { Text("Elegir carpeta") }
                OutlinedButton(onClick = onRescan) { Text("Reintentar") }
            },
        )
        LibraryError.Unreadable -> EmptyState(
            icon = Icons.Outlined.ErrorOutline,
            title = "No se pudo leer la carpeta",
            message = "El proveedor de archivos no respondió. Comprueba la conexión o el almacenamiento y reintenta.",
            actions = {
                Button(onClick = onRescan) { Text("Reintentar") }
                OutlinedButton(onClick = onChooseFolder) { Text("Elegir otra carpeta") }
            },
        )
    }
}

@Composable
private fun GameBrowser(
    entries: List<RomEntry>,
    prefs: LibraryPreferencesData,
    query: String,
    filter: LibraryFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    actions: GameActions,
    scanning: Boolean,
) {
    val visible = remember(entries, prefs, filter, query) { LibraryQuery.visible(entries, prefs, filter, query) }
    val recent = remember(entries, prefs) { LibraryQuery.recent(entries, prefs) }
    Column(Modifier.fillMaxSize()) {
        if (scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().testTag("library-progress").semantics {
                    contentDescription = "Actualizando la biblioteca"
                },
            )
        }
        SearchField(query, onQueryChange)
        FilterRow(filter, onFilterChange)
        if (visible.isEmpty()) {
            NoResults(
                query = query,
                filter = filter,
                allHidden = filter == LibraryFilter.ALL && query.isBlank(),
            )
        } else {
            GameCollection(
                entries = visible,
                recent = if (query.isBlank() && filter == LibraryFilter.ALL) recent else emptyList(),
                prefs = prefs,
                layout = prefs.layout,
                actions = actions,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("library-search"),
        singleLine = true,
        label = { Text("Buscar juegos") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Borrar búsqueda")
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
private fun NoResults(query: String, filter: LibraryFilter, allHidden: Boolean) {
    when {
        query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = "Sin resultados",
            message = "Ningún juego coincide con «${query.trim()}».",
        )
        allHidden -> EmptyState(
            icon = Icons.Outlined.VisibilityOff,
            title = "Todos los juegos están ocultos",
            message = "Muéstralos de nuevo en Ajustes › Biblioteca.",
        )
        filter == LibraryFilter.FAVORITES -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = "Todavía no hay favoritos",
            message = "Marca un juego con la estrella para encontrarlo aquí.",
        )
        else -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = "No hay juegos ${filter.title}",
            message = "Prueba con otro filtro.",
        )
    }
}

@Composable
private fun ViewMenu(
    prefs: LibraryPreferencesData,
    onLayoutChange: (LibraryLayout) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("view-menu")) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Vista y orden")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                "Vista",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LibraryLayout.entries.forEach { layout ->
                MenuChoice(layout.title, selected = prefs.layout == layout) {
                    onLayoutChange(layout)
                    expanded = false
                }
            }
            HorizontalDivider()
            Text(
                "Ordenar por",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LibrarySort.entries.forEach { sort ->
                MenuChoice(sort.title, selected = prefs.sort == sort) {
                    onSortChange(sort)
                    expanded = false
                }
            }
        }
    }
}

@Composable
private fun MenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = "Seleccionado") }
        } else {
            { Box(Modifier.width(24.dp)) }
        },
    )
}

/** Cuadrícula o lista de juegos con su menú de pulsación larga. Compartida con Favoritos. */
@Composable
fun GameCollection(
    entries: List<RomEntry>,
    recent: List<RomEntry>,
    prefs: LibraryPreferencesData,
    layout: LibraryLayout,
    actions: GameActions,
    modifier: Modifier = Modifier,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var hideCandidate by remember { mutableStateOf<RomEntry?>(null) }

    @Composable
    fun Menu(entry: RomEntry) {
        DropdownMenu(expanded = menuFor == entry.id, onDismissRequest = { menuFor = null }) {
            DropdownMenuItem(text = { Text("Ver detalle") }, onClick = {
                menuFor = null
                actions.onOpenDetails(entry)
            })
            DropdownMenuItem(
                text = { Text(if (prefs.isFavorite(entry)) "Quitar de favoritos" else "Añadir a favoritos") },
                onClick = {
                    menuFor = null
                    actions.onToggleFavorite(entry)
                },
            )
            DropdownMenuItem(text = { Text("Ocultar de PocketGB") }, onClick = {
                menuFor = null
                hideCandidate = entry
            })
        }
    }

    val padding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)
    when (layout) {
        LibraryLayout.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(156.dp),
            modifier = modifier.fillMaxSize().testTag("library-collection"),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "recent") {
                    RecentRow(recent, prefs, actions)
                }
            }
            items(entries.size, key = { entries[it].id }) { index ->
                val entry = entries[index]
                Box {
                    GameCard(
                        title = entry.title,
                        subtitle = entry.summary(),
                        favorite = prefs.isFavorite(entry),
                        hasProblem = entry.problem != null,
                        onClick = { actions.onOpenDetails(entry) },
                        onLongClick = { menuFor = entry.id },
                    )
                    Menu(entry)
                }
            }
        }
        LibraryLayout.LIST -> LazyColumn(
            modifier = modifier.fillMaxSize().testTag("library-collection"),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            if (recent.isNotEmpty()) {
                item(key = "recent") { RecentRow(recent, prefs, actions, Modifier.padding(horizontal = 16.dp)) }
            }
            items(entries.size, key = { entries[it].id }) { index ->
                val entry = entries[index]
                Box {
                    GameListItem(
                        title = entry.title,
                        subtitle = entry.summary(),
                        favorite = prefs.isFavorite(entry),
                        hasProblem = entry.problem != null,
                        onClick = { actions.onOpenDetails(entry) },
                        onLongClick = { menuFor = entry.id },
                    )
                    Menu(entry)
                }
            }
        }
    }
    hideCandidate?.let { entry ->
        HideGameDialog(
            title = entry.title,
            onConfirm = {
                hideCandidate = null
                actions.onHide(entry)
            },
            onDismiss = { hideCandidate = null },
        )
    }
}

@Composable
private fun RecentRow(
    recent: List<RomEntry>,
    prefs: LibraryPreferencesData,
    actions: GameActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("recent-row"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Continuar jugando", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(recent.size, key = { recent[it].id }) { index ->
                val entry = recent[index]
                GameCard(
                    title = entry.title,
                    subtitle = entry.summary(),
                    favorite = prefs.isFavorite(entry),
                    hasProblem = entry.problem != null,
                    modifier = Modifier.width(156.dp),
                    onClick = { (actions.onPlay ?: actions.onOpenDetails)(entry) },
                )
            }
        }
    }
}
