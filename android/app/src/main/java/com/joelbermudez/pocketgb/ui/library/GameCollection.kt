package com.joelbermudez.pocketgb.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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

    fun open(entry: RomEntry) {
        menuFor = entry.id
    }

    @Composable
    fun Menu(entry: RomEntry, favorite: Boolean) {
        GameContextMenu(
            expanded = menuFor == entry.id,
            onDismiss = { menuFor = null },
            entry = entry,
            favorite = favorite,
            onPlay = actions.onPlay?.let { play -> { play(entry) } },
            onOpenDetails = { actions.onOpenDetails(entry) },
            onToggleFavorite = { actions.onToggleFavorite(entry) },
            onGameSettings = actions.onGameSettings?.let { settings -> { settings(entry) } },
            onHide = { hideCandidate = entry },
        )
    }
}

@Composable
fun GameMenuHost(actions: GameActions, content: @Composable (GameMenuController) -> Unit) {
    val controller = remember(actions) { GameMenuController(actions) }
    content(controller)
    controller.hideCandidate?.let { entry ->
        HideGameDialog(
            title = entry.title,
            onConfirm = {
                controller.hideCandidate = null
                actions.onHide(entry)
            },
            onDismiss = { controller.hideCandidate = null },
        )
    }
}

/**
 * Cuadrícula o lista de juegos con su menú de pulsación larga. Compartida con Favoritos. [header] y [footer]
 * ocupan una fila completa al principio y al final y se desplazan con el contenido.
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
) {
    GameMenuHost(actions) { menu ->
        when (layout) {
            LibraryLayout.GRID -> BoxWithConstraints(modifier.fillMaxSize()) {
              // Columnas según el ancho y la escala de fuente (R9): con fuente muy grande, una columna con tarjeta horizontal.
              val columns = columnsFor(maxWidth.value, LocalDensity.current.fontScale)
              LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().testTag("library-collection"),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (header != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header(menu) }
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
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
            ) {
                if (header != null) {
                    item(key = "header") { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { header(menu) } }
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
