package com.joelbermudez.pocketgb.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.library.LibraryCategory
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.components.RenameGameHost
import com.joelbermudez.pocketgb.ui.components.rememberArtworkStore
import com.joelbermudez.pocketgb.ui.details.GameSettingsHost

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenDetails: (String) -> Unit,
    /** Acción principal: «Continuar» exacto si [resumable] lo permite, si no «Jugar» (quien llama elige el modo). */
    onPlay: (RomEntry) -> Unit,
    gameplaySettings: GameplaySettingsRepository? = null,
    /** A9: «Jugar desde el inicio» (solo la partida). */
    onPlayFromStart: ((RomEntry) -> Unit)? = null,
    /** A9: huellas con «Continuar» exacto disponible. */
    resumable: Set<String> = emptySet(),
    /** N4: abre la pantalla de una categoría. */
    onOpenCategory: (LibraryCategory) -> Unit = {},
    /** N4: «Ver todo» de la fila de Favoritos. */
    onOpenFavorites: () -> Unit = {},
    /** N4: «Partida» del centro de ajustes del juego (Ajustes › Partidas de esa huella). */
    onOpenSaves: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val tag by viewModel.tag.collectAsStateWithLifecycle()
    val summary by viewModel.newGamesSummary.collectAsStateWithLifecycle()
    val preferencesReadOnly by viewModel.preferencesReadOnly.collectAsStateWithLifecycle()
    val chooseFolder = rememberFolderPicker(viewModel::chooseFolder)
    var settingsFor by remember { mutableStateOf<RomEntry?>(null) }
    var renameFor by remember { mutableStateOf<RomEntry?>(null) }
    val artwork = rememberArtworkStore()
    // Al volver (p. ej. tras borrar portadas en Ajustes) se relee el disco.
    LifecycleResumeEffect(artwork) {
        artwork.refresh()
        onPauseOrDispose {}
    }
    // N5A-2: al terminar un escaneo se purgan las copias de imágenes de la carpeta que ya no corresponden a nada.
    val covers = com.joelbermudez.pocketgb.ui.components.rememberCoverRepository()
    val ready = state as? com.joelbermudez.pocketgb.library.LibraryState.Ready
    androidx.compose.runtime.LaunchedEffect(ready?.entries) {
        val entries = ready?.entries ?: return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { covers.pruneFolderCache(entries) }
    }
    // Se lee en la composición del menú (State): cambia sin recrear las acciones ni cerrar un menú abierto.
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
    LibraryContent(
        state = state,
        prefs = prefs,
        query = query,
        filter = filter,
        onQueryChange = viewModel::setQuery,
        onFilterChange = viewModel::setFilter,
        onOpenCategory = onOpenCategory,
        onOpenFavorites = onOpenFavorites,
        tag = tag,
        onTagChange = viewModel::setTag,
        onLayoutChange = viewModel::setLayout,
        onSortChange = viewModel::setSort,
        onChooseFolder = chooseFolder,
        onRescan = viewModel::rescan,
        actions = actions,
        newGamesSummary = summary,
        onNewGamesSummaryShown = viewModel::dismissNewGamesSummary,
        preferencesReadOnly = preferencesReadOnly,
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
