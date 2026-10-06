package com.joelbermudez.pocketgb.ui.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.LibraryFilter
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.components.EmptyState
import com.joelbermudez.pocketgb.ui.components.GameArtwork
import com.joelbermudez.pocketgb.ui.components.relativeDateText
import com.joelbermudez.pocketgb.ui.details.GameSettingsHost
import com.joelbermudez.pocketgb.ui.library.GameActions
import com.joelbermudez.pocketgb.ui.library.GameCollection
import com.joelbermudez.pocketgb.ui.library.GameMenuController
import com.joelbermudez.pocketgb.ui.library.ScanningPane

/** Cuántos «jugados recientemente» muestra el carril de Favoritos (con o sin portada capturada). */
private const val RECENT_LIMIT = 10

@Composable
fun FavoritesScreen(
    viewModel: LibraryViewModel,
    onOpenDetails: (String) -> Unit,
    onPlay: ((RomEntry) -> Unit)? = null,
    gameplaySettings: GameplaySettingsRepository? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    var settingsFor by remember { mutableStateOf<RomEntry?>(null) }
    val actions = remember(viewModel, onOpenDetails, onPlay) {
        GameActions(
            onOpenDetails = { onOpenDetails(it.id) },
            onToggleFavorite = viewModel::toggleFavorite,
            onHide = viewModel::hide,
            onPlay = onPlay,
            onGameSettings = { settingsFor = it },
        )
    }
    FavoritesContent(state = state, prefs = prefs, actions = actions)
    GameSettingsHost(
        entry = settingsFor,
        library = viewModel,
        repository = gameplaySettings ?: GameplaySettingsRepository.shared(LocalContext.current),
        onDismiss = { settingsFor = null },
    )
}

/**
 * Favoritos: la misma fuente que Biblioteca. Carril de jugados recientemente y cuadrícula de favoritos, con su
 * propio estado vacío.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesContent(
    state: LibraryState,
    prefs: LibraryPreferencesData,
    actions: GameActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.favorites_title)) }) },
    ) { padding ->
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
                entries == null || entries.isEmpty() && state is LibraryState.Scanning -> ScanningPane(
                    message = if (state is LibraryState.Loading) "Cargando biblioteca…" else "Buscando juegos…",
                )
                else -> {
                    val favorites = remember(entries, prefs) {
                        LibraryQuery.visible(entries, prefs, LibraryFilter.FAVORITES, "")
                    }
                    val recent = remember(entries, prefs) { LibraryQuery.recent(entries, prefs, limit = RECENT_LIMIT) }
                    if (favorites.isEmpty() && recent.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.StarBorder,
                            title = stringResource(R.string.library_no_favorites_title),
                            message = stringResource(R.string.favorites_empty_message),
                        )
                    } else {
                        GameCollection(
                            entries = favorites,
                            prefs = prefs,
                            layout = prefs.layout,
                            actions = actions,
                            header = { menu ->
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (recent.isNotEmpty()) RecentStrip(recent, prefs, actions, menu)
                                    Text(
                                        stringResource(R.string.favorites_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (favorites.isEmpty()) {
                                        Text(
                                            stringResource(R.string.favorites_hint_add),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            footer = if (favorites.isEmpty()) {
                                null
                            } else {
                                {
                                    Text(
                                        stringResource(R.string.favorites_hint_remove),
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** «Jugados recientemente»: miniaturas de 96 dp con la fecha; mantener pulsado abre el menú del juego. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentStrip(
    recent: List<RomEntry>,
    prefs: LibraryPreferencesData,
    actions: GameActions,
    menu: GameMenuController,
) {
    Column(Modifier.testTag("favorites-recent"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.recent_played_title), style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(recent, key = { it.id }) { entry ->
                val lastPlayed = prefs.lastPlayedAt(entry)
                val label = stringResource(R.string.game_details_action)
                val description = listOfNotNull(entry.title, lastPlayed?.let { relativeDateText(it) }).joinToString(", ")
                Box {
                    Column(
                        Modifier
                            .width(96.dp)
                            .testTag("recent-item")
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                contentDescription = description
                            }
                            .combinedClickable(
                                onClickLabel = label,
                                onLongClick = { menu.open(entry) },
                                onClick = { actions.onOpenDetails(entry) },
                            ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        GameArtwork(entry, prefs.fingerprints[entry.id], Modifier.fillMaxWidth(), compact = true)
                        if (lastPlayed != null) {
                            Text(
                                relativeDateText(lastPlayed),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    menu.Menu(entry, prefs.isFavorite(entry))
                }
            }
        }
    }
}
