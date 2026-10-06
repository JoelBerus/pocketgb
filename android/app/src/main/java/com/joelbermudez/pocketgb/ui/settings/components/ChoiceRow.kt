package com.joelbermudez.pocketgb.ui.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Elección única con botones segmentados (pocas opciones). Los botones miden al menos 48 dp. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.heightIn(min = 48.dp).let { if (tag != null) it.testTag("$tag-$index") else it },
                ) { Text(label, maxLines = 2) }
            }
        }
    }
}

/** Elección única con menú desplegable (muchas opciones, p. ej. paletas). */
@Composable
fun <T> DropdownRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(current) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (enabled) ListItemDefaults.colors().headlineColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        modifier = modifier
            .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
            .let { if (tag != null) it.testTag(tag) else it },
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        options.forEachIndexed { index, (value, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    open = false
                    onSelect(value)
                },
                modifier = Modifier.heightIn(min = 48.dp).let { if (tag != null) it.testTag("$tag-$index") else it },
            )
        }
    }
}

/** Fila con interruptor: toda la fila es el objetivo táctil y se anuncia como un solo interruptor. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .let { if (tag != null) it.testTag(tag) else it },
    )
}
