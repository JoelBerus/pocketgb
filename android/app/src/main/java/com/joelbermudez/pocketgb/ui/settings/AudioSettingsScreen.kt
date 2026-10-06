package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.settings.components.SettingsGroup
import com.joelbermudez.pocketgb.ui.settings.components.SettingsPage
import com.joelbermudez.pocketgb.ui.settings.components.SliderRow
import com.joelbermudez.pocketgb.ui.settings.components.ValueRow
import kotlin.math.roundToInt

/**
 * Ajustes › Audio: volumen del juego. Sin «sonar con el modo silencio» (K1): en Android el audio sigue el
 * volumen multimedia del sistema.
 */
@Composable
fun AudioSettingsScreen(repository: GameplaySettingsRepository, onBack: () -> Unit) {
    val data by repository.state.collectAsStateWithLifecycle()
    val failure by repository.persistFailure.collectAsStateWithLifecycle()
    AudioSettingsContent(data, repository::update, onBack, warning = persistWarning(failure != null))
}

@Composable
fun AudioSettingsContent(
    data: GameplaySettingsData,
    onUpdate: ((GameplaySettingsData) -> GameplaySettingsData) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    warning: String? = null,
) {
    SettingsPage(stringResource(R.string.settings_audio), onBack, modifier, warning) {
        SettingsGroup(footer = stringResource(R.string.audio_footer_system)) {
            SliderRow(
                title = stringResource(R.string.audio_volume_label),
                value = data.volume,
                valueText = stringResource(R.string.audio_volume_value, (data.volume * 100).roundToInt()),
                // Pasos de 0,05 (19 marcas intermedias entre 0 y 1).
                onValueChange = { value -> onUpdate { it.copy(volume = (value * 20f).roundToInt() / 20f) } },
                steps = 19,
                startIcon = Icons.AutoMirrored.Filled.VolumeDown,
                endIcon = Icons.AutoMirrored.Filled.VolumeUp,
                tag = "audio-volume",
            )
        }
        SettingsGroup(footer = stringResource(R.string.audio_background_footer)) {
            ValueRow(stringResource(R.string.audio_background_label), stringResource(R.string.audio_background_value))
        }
    }
}
