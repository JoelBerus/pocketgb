package com.joelbermudez.pocketgb.ui.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.GameCollection
import com.joelbermudez.pocketgb.ui.library.ScanningPane

@Composable
fun FavoritesScreen(viewModel: LibraryViewModel, onOpenDetails: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenDetails) {
        GameActions(
            onOpenDetails = { onOpenDetails(it.id) },
            onToggleFavorite = viewModel::toggleFavorite,
            onHide = viewModel::hide,
        )
    }
    FavoritesContent(state = state, prefs = prefs, actions = actions)
}

/** Favoritos: la misma fuente que Biblioteca, filtrada por estrella, con su propio estado vacío. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesContent(
    state: LibraryState,
    prefs: LibraryPreferencesData,
    actions: GameActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Favoritos") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val entries = when (state) {
                is LibraryState.Ready -> state.entries
                is LibraryState.Scanning -> state.previous
                else -> null
            }
            when {
                entries == null && state is LibraryState.NoFolder || state is LibraryState.Failed -> EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = "Sin carpeta de juegos",
                    message = "Elige o repara la carpeta en Biblioteca para ver aquí tus favoritos.",
                )
                entries == null || entries.isEmpty() && state is LibraryState.Scanning -> ScanningPane()
                else -> {
                    val favorites = remember(entries, prefs) {
                        LibraryQuery.visible(entries, prefs, LibraryFilter.FAVORITES, "")
                    }
                    if (favorites.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.StarBorder,
                            title = "Todavía no hay favoritos",
                            message = "Marca un juego con una estrella para encontrarlo aquí.",
                        )
                    } else {
                        GameCollection(
                            entries = favorites,
                            recent = emptyList(),
                            prefs = prefs,
                            layout = prefs.layout,
                            actions = actions,
                        )
                    }
                }
            }
        }
    }
}
