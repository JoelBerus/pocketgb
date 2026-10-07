package com.joelbermudez.pocketgb.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.library.LibraryLayout
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.components.GameCard
import com.joelbermudez.pocketgb.ui.components.GameContextMenu
import com.joelbermudez.pocketgb.ui.components.GameListItem
import com.joelbermudez.pocketgb.ui.components.HideGameDialog

/** Acciones sobre un juego, comunes a Biblioteca y Favoritos. */
class GameActions(
    val onOpenDetails: (RomEntry) -> Unit,
    val onToggleFavorite: (RomEntry) -> Unit,
    val onHide: (RomEntry) -> Unit,
    /** Abre el juego directamente («Continuar», «Jugar» del menú); sin valor, no se ofrece jugar desde la lista. */
    val onPlay: ((RomEntry) -> Unit)? = null,
    /** Abre los ajustes del juego (hoja por juego); sin valor, el menú no los ofrece. */
    val onGameSettings: ((RomEntry) -> Unit)? = null,
    /** A9: «Jugar desde el inicio» (solo la partida, sin el estado automático); se ofrece si [canResume]. */
    val onPlayFromStart: ((RomEntry) -> Unit)? = null,
    /** A9: «Renombrar» (alias visual); sin valor, el menú no lo ofrece. */
    val onRename: ((RomEntry) -> Unit)? = null,
    /** A9: el juego tiene «Continuar» exacto (estado automático vigente). */
    val canResume: (RomEntry) -> Boolean = { false },
    /** N6: «Momentos» del menú; sin valor, el menú abre el detalle (que tiene «Momentos»). */
    val onOpenMoments: ((RomEntry) -> Unit)? = null,
)

/**
 * Menú contextual y diálogo de ocultar compartidos por todo lo que muestra juegos (cuadrícula, lista, carril
 * de Favoritos). Quien dibuja un juego llama a [open] en la pulsación larga y a [Menu] dentro de su `Box`.
 */
@Stable
class GameMenuController internal constructor(private val actions: GameActions) {
    var menuFor by mutableStateOf<String?>(null)
        internal set
    var hideCandidate by mutableStateOf<RomEntry?>(null)
        internal set

    /**
     * Abre el menú de [entry] en [place] (N4: el mismo juego puede salir en una estantería del inicio y en la cuadrícula;
     * solo se abre el menú del sitio que se pulsó). `""` = la cuadrícula o la lista.
     */
    fun open(entry: RomEntry, place: String = "") {
        menuFor = key(entry, place)
    }

    private fun key(entry: RomEntry, place: String) = if (place.isEmpty()) entry.id else "$place|${entry.id}"

    @Composable
    fun Menu(entry: RomEntry, favorite: Boolean, place: String = "") {
        GameContextMenu(
            expanded = menuFor == key(entry, place),
            onDismiss = { menuFor = null },
            entry = entry,
            favorite = favorite,
            onPlay = actions.onPlay?.let { play -> { play(entry) } },
            onOpenDetails = { actions.onOpenDetails(entry) },
            onToggleFavorite = { actions.onToggleFavorite(entry) },
            onGameSettings = actions.onGameSettings?.let { settings -> { settings(entry) } },
            onHide = { hideCandidate = entry },
            canResume = actions.canResume(entry),
            onPlayFromStart = actions.onPlayFromStart?.let { play -> { play(entry) } },
            onRename = actions.onRename?.let { rename -> { rename(entry) } },
            onOpenMoments = { (actions.onOpenMoments ?: actions.onOpenDetails)(entry) },
        )
    }
}

/** Id del juego cuyo menú contextual arranca abierto; solo lo fija el catálogo de capturas (en la app es `null`). */
val LocalInitialMenuFor = staticCompositionLocalOf<String?> { null }

@Composable
fun GameMenuHost(actions: GameActions, content: @Composable (GameMenuController) -> Unit) {
    val initialMenu = LocalInitialMenuFor.current
    val controller = remember(actions) { GameMenuController(actions).also { it.menuFor = initialMenu } }
    content(controller)
    controller.hideCandidate?.let { entry ->
        HideGameDialog(
            title = entry.displayTitle,
            onConfirm = {
                controller.hideCandidate = null
                actions.onHide(entry)
            },
            onDismiss = { controller.hideCandidate = null },
        )
    }
}

/** N4: una fila completa con clave propia antes del título de sección (Favoritos, estanterías del inicio…). */
class CollectionItem(val key: String, val content: @Composable (GameMenuController) -> Unit)

/**
 * Cuadrícula o lista de juegos con su menú de pulsación larga. Compartida con Favoritos. [header] y [footer]
 * ocupan una fila completa al principio y al final y se desplazan con el contenido. [leadingItems] (N4) van tras
 * [header], cada una en su fila. [pinnedHeader] (N3b, horizontal) va después y se queda fijo arriba al desplazar
 * (título de sección). [gridState]/[listState] permiten conservar la posición al volver de una búsqueda;
 * [bottomPadding] deja aire bajo la última fila (barra flotante).
 */
@Composable
fun GameCollection(
    entries: List<RomEntry>,
    prefs: LibraryPreferencesData,
    layout: LibraryLayout,
    actions: GameActions,
    modifier: Modifier = Modifier,
    header: (@Composable (GameMenuController) -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    pinnedHeader: (@Composable () -> Unit)? = null,
    gridState: LazyGridState? = null,
    listState: LazyListState? = null,
    bottomPadding: Dp = 16.dp,
    leadingItems: List<CollectionItem> = emptyList(),
) {
    GameMenuHost(actions) { menu ->
        when (layout) {
            LibraryLayout.GRID -> BoxWithConstraints(modifier.fillMaxSize()) {
              // Columnas según el ancho y la escala de fuente (R9): con fuente muy grande, una columna con tarjeta horizontal.
              val columns = columnsFor(maxWidth.value, LocalDensity.current.fontScale)
              LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().testTag("library-collection"),
                state = gridState ?: rememberLazyGridState(),
                contentPadding = PaddingValues(
                    start = GRID_MARGIN_DP.dp, top = 12.dp, end = GRID_MARGIN_DP.dp, bottom = bottomPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (header != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header(menu) }
                }
                leadingItems.forEach { leading ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "leading-${leading.key}") { leading.content(menu) }
                }
                if (pinnedHeader != null) {
                    stickyHeader(key = "pinned-header") { pinnedHeader() }
                }
                items(entries.size, key = { entries[it].id }) { index ->
                    val entry = entries[index]
                    val favorite = prefs.isFavorite(entry)
                    Box {
                        GameCard(
                            entry = entry,
                            fingerprint = prefs.fingerprints[entry.id],
                            favorite = favorite,
                            lastPlayedAt = prefs.lastPlayedAt(entry),
                            horizontal = columns == 1,
                            onClick = { actions.onOpenDetails(entry) },
                            onLongClick = { menu.open(entry) },
                        )
                        menu.Menu(entry, favorite)
                    }
                }
                if (footer != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "footer") { footer() }
                }
              }
            }
            LibraryLayout.LIST -> LazyColumn(
                modifier = modifier.fillMaxSize().testTag("library-collection"),
                state = listState ?: rememberLazyListState(),
                contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
            ) {
                if (header != null) {
                    item(key = "header") { Box(Modifier.padding(horizontal = GRID_MARGIN_DP.dp, vertical = 8.dp)) { header(menu) } }
                }
                leadingItems.forEach { leading ->
                    item(key = "leading-${leading.key}") {
                        Box(Modifier.padding(horizontal = GRID_MARGIN_DP.dp, vertical = 8.dp)) { leading.content(menu) }
                    }
                }
                if (pinnedHeader != null) {
                    stickyHeader(key = "pinned-header") { Box(Modifier.padding(horizontal = GRID_MARGIN_DP.dp)) { pinnedHeader() } }
                }
                items(entries.size, key = { entries[it].id }) { index ->
                    val entry = entries[index]
                    val favorite = prefs.isFavorite(entry)
                    Box {
                        GameListItem(
                            entry = entry,
                            fingerprint = prefs.fingerprints[entry.id],
                            favorite = favorite,
                            lastPlayedAt = prefs.lastPlayedAt(entry),
                            onClick = { actions.onOpenDetails(entry) },
                            onLongClick = { menu.open(entry) },
                        )
                        menu.Menu(entry, favorite)
                    }
                }
                if (footer != null) {
                    item(key = "footer") { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { footer() } }
                }
            }
        }
    }
}
