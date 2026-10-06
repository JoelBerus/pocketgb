package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    appearance: AppearanceState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_appearance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_appearance_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            items(ThemeMode.entries, key = { it.name }) { mode ->
                val label = when (mode) {
                    ThemeMode.SYSTEM -> "Sistema"
                    ThemeMode.LIGHT -> "Claro"
                    ThemeMode.DARK -> "Oscuro"
                }
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = {
                        RadioButton(
                            selected = appearance.themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                        )
                    },
                    modifier = Modifier.clickable { onThemeModeChange(mode) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_appearance_dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.settings_appearance_dynamic_color_summary)) },
                    trailingContent = {
                        Switch(
                            checked = appearance.dynamicColor,
                            onCheckedChange = onDynamicColorChange,
                        )
                    },
                )
            }
            item {
                Text(
                    stringResource(R.string.appearance_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
