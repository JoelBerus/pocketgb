package com.joelbermudez.pocketgb.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry

@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onOpenDetails: (String) -> Unit, onPlay: (RomEntry) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val chooseFolder = rememberFolderPicker(viewModel::chooseFolder)
    val actions = remember(viewModel, onOpenDetails, onPlay) {
        GameActions(
            onOpenDetails = { onOpenDetails(it.id) },
            onToggleFavorite = viewModel::toggleFavorite,
            onHide = viewModel::hide,
            onPlay = onPlay,
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
    )
}
