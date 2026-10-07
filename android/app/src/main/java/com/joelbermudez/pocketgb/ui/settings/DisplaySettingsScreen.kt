package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.components.GamePlaceholder
import com.joelbermudez.pocketgb.ui.settings.components.SettingsGroup
import com.joelbermudez.pocketgb.ui.settings.components.SettingsPage
import com.joelbermudez.pocketgb.ui.settings.components.SwitchRow
import com.joelbermudez.pocketgb.ui.settings.components.ValueRow

/** Ajustes › Pantalla: vista previa 10:9 con píxeles nítidos y escala entera en horizontal. */
@Composable
fun DisplaySettingsScreen(repository: GameplaySettingsRepository, onBack: () -> Unit) {
    val data by repository.state.collectAsStateWithLifecycle()
    val failure by repository.persistFailure.collectAsStateWithLifecycle()
    DisplaySettingsContent(data, repository::update, onBack, warning = persistWarning(failure != null))
}

@Composable
fun DisplaySettingsContent(
    data: GameplaySettingsData,
    onUpdate: ((GameplaySettingsData) -> GameplaySettingsData) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    warning: String? = null,
) {
    val previewDescription = stringResource(R.string.display_preview_description)
    SettingsPage(stringResource(R.string.settings_display), onBack, modifier, warning) {
        SettingsGroup(footer = stringResource(R.string.display_preview_footer)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(Color.Black)
                    .padding(16.dp)
                    .semantics { contentDescription = previewDescription },
                contentAlignment = Alignment.Center,
            ) {
                // Arte generado y determinista (nunca material de un juego), en la proporción 10:9 del Game Boy.
                GamePlaceholder(
                    seed = "pocketgb-display-preview",
                    title = "PocketGB",
                    isColor = false,
                    modifier = Modifier.aspectRatio(10f / 9f),
                )
            }
        }
        SettingsGroup(footer = stringResource(R.string.display_scale_footer)) {
            SwitchRow(
                title = stringResource(R.string.display_integer_scale),
                checked = data.integerScaleLandscape,
                onCheckedChange = { value -> onUpdate { it.copy(integerScaleLandscape = value) } },
                tag = "display-integer-scale",
            )
            ValueRow(
                stringResource(R.string.display_filter_label),
                stringResource(R.string.display_filter_value),
            )
        }
    }
}
