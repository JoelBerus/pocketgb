package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.ByteFormat
import com.joelbermudez.pocketgb.settings.StorageUsage
import com.joelbermudez.pocketgb.ui.settings.components.SettingsGroup
import com.joelbermudez.pocketgb.ui.settings.components.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ajustes › Almacenamiento: partidas, estados y portadas por separado. Solo se ofrece borrar portadas. */
@Composable
fun StorageSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var refresh by remember { mutableIntStateOf(0) }
    val usage by produceState<StorageUsage?>(null, refresh) {
        value = withContext(Dispatchers.IO) { StorageUsage.measure(context.filesDir) }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    StorageSettingsContent(
        usage = usage,
        formatSize = { ByteFormat.format(it) },
        onClearArtwork = {
            scope.launch {
                withContext(Dispatchers.IO) { StorageUsage.clearArtwork(context.filesDir) }
                refresh++
                snackbar.showSnackbar(resources.getString(R.string.storage_cleared))
            }
        },
        snackbar = snackbar,
        onBack = onBack,
    )
}

/** Contenido sin E/S: [usage] `null` = midiendo. */
@Composable
fun StorageSettingsContent(
    usage: StorageUsage?,
    formatSize: (Long) -> String,
    onClearArtwork: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
) {
    var confirmClear by remember { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.settings_storage), onBack, modifier, snackbar = snackbar) {
        SettingsGroup(
            header = stringResource(R.string.storage_header),
            footer = stringResource(R.string.storage_footer),
        ) {
            SizeRow(stringResource(R.string.storage_saves), usage?.saves, formatSize, "storage-saves")
            SizeRow(stringResource(R.string.storage_states), usage?.states, formatSize, "storage-states")
            SizeRow(stringResource(R.string.storage_artwork), usage?.artwork, formatSize, "storage-artwork")
        }
        val canClear = (usage?.artwork ?: 0L) > 0L
        SettingsGroup(footer = stringResource(R.string.storage_clear_footer)) {
            val color = if (canClear) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ListItem(
                headlineContent = { Text(stringResource(R.string.storage_clear_artwork)) },
                leadingContent = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = color, leadingIconColor = color),
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .clickable(enabled = canClear) { confirmClear = true }
                    .testTag("storage-clear-artwork"),
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.storage_clear_title)) },
            text = { Text(stringResource(R.string.storage_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearArtwork()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("storage-clear-confirm"),
                ) { Text(stringResource(R.string.storage_clear_artwork), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun SizeRow(title: String, bytes: Long?, formatSize: (Long) -> String, tag: String) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            if (bytes != null) {
                Text(formatSize(bytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag(tag),
    )
}
