package com.joelbermudez.pocketgb.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.BuildConfig
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.ui.settings.components.SettingsGroup
import com.joelbermudez.pocketgb.ui.settings.components.SettingsPage
import com.joelbermudez.pocketgb.ui.settings.components.ValueRow

/** Acerca de: versión, núcleo, privacidad sin red, juegos propios y licencias de terceros. */
@Composable
fun AboutScreen(onBack: () -> Unit, onLicenses: () -> Unit = {}) {
    SettingsPage(stringResource(R.string.settings_about), onBack) {
        SettingsGroup {
            ValueRow(
                stringResource(R.string.about_version),
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                Modifier.testTag("about-version"),
            )
            ValueRow(stringResource(R.string.about_core), stringResource(R.string.about_core_value))
            ValueRow(stringResource(R.string.about_consoles), stringResource(R.string.about_consoles_value))
        }
        SettingsGroup(header = stringResource(R.string.about_privacy_header)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_privacy_body)) },
                leadingContent = { Icon(Icons.Outlined.WifiOff, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        SettingsGroup(header = stringResource(R.string.about_games_header)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_games_body)) },
                leadingContent = { Icon(Icons.Outlined.SportsEsports, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        SettingsGroup {
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_licenses)) },
                leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.heightIn(min = 56.dp).clickable(onClick = onLicenses).testTag("about-licenses"),
            )
        }
    }
}
