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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val summary by viewModel.newGamesSummary.collectAsStateWithLifecycle()
    val chooseFolder = rememberFolderPicker(viewModel::chooseFolder)
    var settingsFor by remember { mutableStateOf<RomEntry?>(null) }
    var renameFor by remember { mutableStateOf<RomEntry?>(null) }
    val artwork = rememberArtworkStore()
    // Al volver (p. ej. tras borrar portadas en Ajustes) se relee el disco.
    LifecycleResumeEffect(artwork) {
        artwork.refresh()
        onPauseOrDispose {}
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
        onLayoutChange = viewModel::setLayout,
        onSortChange = viewModel::setSort,
        onChooseFolder = chooseFolder,
        onRescan = viewModel::rescan,
        actions = actions,
        newGamesSummary = summary,
        onNewGamesSummaryShown = viewModel::dismissNewGamesSummary,
    )
    GameSettingsHost(
        entry = settingsFor,
        library = viewModel,
        repository = gameplaySettings ?: GameplaySettingsRepository.shared(LocalContext.current),
        onDismiss = { settingsFor = null },
    )
    RenameGameHost(entry = renameFor, prefs = prefs, onSetAlias = viewModel::setAlias, onDismiss = { renameFor = null })
}
