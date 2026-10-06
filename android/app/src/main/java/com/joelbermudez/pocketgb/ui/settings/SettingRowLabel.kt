package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R

/** Título de un ajuste por juego con su estado en texto («Global» o «Personalizado»): nunca solo con color. */
@Composable
fun SettingRowLabel(title: String, customized: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (customized) Icons.Outlined.Tune else Icons.Outlined.Language,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (customized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(if (customized) R.string.setting_customized else R.string.setting_global),
                style = MaterialTheme.typography.labelMedium,
                color = if (customized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
