package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R

private class SettingEntry(val label: Int, val icon: ImageVector, val onClick: () -> Unit)

/** Ajustes agrupado en tres secciones con iconos, como `I/Settings/SettingsView.swift`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onAppearance: () -> Unit,
    onLibrary: () -> Unit,
    onSaves: () -> Unit,
    onAbout: () -> Unit,
    onEmulation: () -> Unit = {},
    onControls: () -> Unit = {},
    onAudio: () -> Unit = {},
    onDisplay: () -> Unit = {},
    onStorage: () -> Unit = {},
    /** N9: Ajustes › Guía. */
    onGuide: () -> Unit = {},
) {
    val groups = listOf(
        R.string.settings_group_game to listOf(
            SettingEntry(R.string.settings_emulation, Icons.Outlined.Memory, onEmulation),
            SettingEntry(R.string.settings_controls, Icons.Outlined.SportsEsports, onControls),
            SettingEntry(R.string.settings_audio, Icons.AutoMirrored.Outlined.VolumeUp, onAudio),
            SettingEntry(R.string.settings_display, Icons.Outlined.StayCurrentPortrait, onDisplay),
        ),
        R.string.settings_group_library to listOf(
            SettingEntry(R.string.settings_library, Icons.Outlined.FolderOpen, onLibrary),
            SettingEntry(R.string.settings_saves, Icons.Outlined.Save, onSaves),
            SettingEntry(R.string.settings_storage, Icons.Outlined.Storage, onStorage),
        ),
        null to listOf(
            SettingEntry(R.string.settings_appearance, Icons.Outlined.Palette, onAppearance),
            SettingEntry(R.string.n9_settings_guide, Icons.AutoMirrored.Outlined.MenuBook, onGuide),
            SettingEntry(R.string.settings_about, Icons.Outlined.Info, onAbout),
        ),
    )
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("settings-list"),
            contentPadding = padding,
        ) {
            groups.forEachIndexed { index, (header, entries) ->
                if (header != null) {
                    item(key = "header-$index") {
                        Text(
                            stringResource(header),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                                .semantics { heading() },
                        )
                    }
                } else {
                    item(key = "divider-$index") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                }
                entries.forEach { entry ->
                    item(key = entry.label) {
                        ListItem(
                            headlineContent = { Text(stringResource(entry.label)) },
                            leadingContent = { Icon(entry.icon, contentDescription = null) },
                            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                            modifier = Modifier.heightIn(min = 56.dp).clickable(onClick = entry.onClick),
                        )
                    }
                }
            }
        }
    }
}
