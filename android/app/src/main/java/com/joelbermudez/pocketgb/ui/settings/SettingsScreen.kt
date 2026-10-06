package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

private data class SettingItem(val label: String, val action: SettingAction)
private enum class SettingAction { APPEARANCE, LIBRARY, SAVES, ABOUT, LATER }

private val settingItems = listOf(
    SettingItem("Emulación", SettingAction.LATER),
    SettingItem("Controles", SettingAction.LATER),
    SettingItem("Audio", SettingAction.LATER),
    SettingItem("Pantalla", SettingAction.LATER),
    SettingItem("Biblioteca", SettingAction.LIBRARY),
    SettingItem("Partidas", SettingAction.SAVES),
    SettingItem("Almacenamiento", SettingAction.LATER),
    SettingItem("Apariencia", SettingAction.APPEARANCE),
    SettingItem("Acerca de", SettingAction.ABOUT),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onAppearance: () -> Unit, onLibrary: () -> Unit, onSaves: () -> Unit, onAbout: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Ajustes") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("settings-list"),
            contentPadding = padding,
        ) {
            items(settingItems, key = { it.label }) { item ->
                ListItem(
                    headlineContent = { Text(item.label) },
                    modifier = Modifier.clickable {
                        when (item.action) {
                            SettingAction.APPEARANCE -> onAppearance()
                            SettingAction.LIBRARY -> onLibrary()
                            SettingAction.SAVES -> onSaves()
                            SettingAction.ABOUT -> onAbout()
                            SettingAction.LATER -> scope.launch {
                                snackbar.showSnackbar("Disponible en próximos hitos")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
