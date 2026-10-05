package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.library.LibraryError
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.ui.library.rememberFolderPicker

@Composable
fun LibrarySettingsScreen(viewModel: LibraryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    val chooseFolder = rememberFolderPicker(viewModel::chooseFolder)
    val entries = when (val current = state) {
        is LibraryState.Ready -> current.entries
        is LibraryState.Scanning -> current.previous
        else -> emptyList()
    }
    LibrarySettingsContent(
        state = state,
        folderName = folderName,
        hidden = remember(entries, prefs) { LibraryQuery.hidden(entries, prefs) },
        onChooseFolder = chooseFolder,
        onRescan = viewModel::rescan,
        onForget = viewModel::forgetFolder,
        onUnhide = viewModel::unhide,
        onBack = onBack,
    )
}

private fun statusText(state: LibraryState): String = when (state) {
    LibraryState.Loading -> "Cargando…"
    LibraryState.NoFolder -> "Sin carpeta elegida"
    is LibraryState.Scanning -> "Buscando juegos…"
    is LibraryState.Ready -> when (state.entries.size) {
        0 -> "Sin juegos"
        1 -> "1 juego"
        else -> "${state.entries.size} juegos"
    }
    is LibraryState.Failed -> when (state.error) {
        LibraryError.PermissionRevoked -> "Permiso revocado: vuelve a elegir la carpeta"
        LibraryError.FolderMissing -> "La carpeta ya no existe"
        LibraryError.Unreadable -> "No se pudo leer la carpeta"
        LibraryError.AccessNotKept -> "No se pudo conservar el acceso a la carpeta"
    }
}

/** Ajustes › Biblioteca sin ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsContent(
    state: LibraryState,
    folderName: String?,
    hidden: List<RomEntry>,
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit,
    onForget: () -> Unit,
    onUnhide: (RomEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmForget by remember { mutableStateOf(false) }
    val hasFolder = state != LibraryState.NoFolder && state != LibraryState.Loading
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Biblioteca") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("library-settings"), contentPadding = padding) {
            item {
                ListItem(
                    headlineContent = { Text("Carpeta actual") },
                    supportingContent = {
                        Text(
                            if (hasFolder) "${folderName ?: "Sin nombre"} · ${statusText(state)}" else statusText(state),
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text(if (hasFolder) "Cambiar carpeta" else "Elegir carpeta") },
                    leadingContent = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onChooseFolder).testTag("settings-choose-folder"),
                )
            }
            if (hasFolder) {
                item {
                    ListItem(
                        headlineContent = { Text("Volver a escanear") },
                        leadingContent = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onRescan).testTag("settings-rescan"),
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("Olvidar carpeta") },
                        supportingContent = { Text("No borra ningún archivo, solo deja de usar la carpeta.") },
                        leadingContent = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        colors = ListItemDefaults.colors(
                            headlineColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.clickable { confirmForget = true }.testTag("settings-forget"),
                    )
                }
            }
            item {
                HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text("Juegos ocultos", style = MaterialTheme.typography.titleMedium)
                    },
                )
            }
            if (hidden.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text("Ningún juego oculto") },
                        supportingContent = { Text("Los juegos que ocultes aparecerán aquí.") },
                        modifier = Modifier.testTag("settings-no-hidden"),
                    )
                }
            } else {
                items(hidden, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.id) },
                        trailingContent = {
                            TextButton(onClick = { onUnhide(entry) }, modifier = Modifier.testTag("unhide-${entry.id}")) {
                                Text("Mostrar de nuevo")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("¿Olvidar la carpeta?") },
            text = {
                Text(
                    "PocketGB dejará de usarla y soltará el permiso. No se borra ningún ROM ni partida; " +
                        "puedes elegirla de nuevo cuando quieras.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForget()
                }) { Text("Olvidar") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
        )
    }
}
