package com.joelbermudez.pocketgb.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.emulator.GbaBiosStatus
import androidx.compose.runtime.setValue
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.settings.MAX_COMPAT_PALETTE
import com.joelbermudez.pocketgb.ui.settings.components.DropdownRow
import com.joelbermudez.pocketgb.ui.settings.components.SettingsGroup
import com.joelbermudez.pocketgb.ui.settings.components.SettingsPage
import com.joelbermudez.pocketgb.ui.settings.components.SwitchRow
import com.joelbermudez.pocketgb.ui.settings.components.ValueRow

/** Nombre de una paleta de compatibilidad: 0 = «Automática»; 1..12 = combinaciones de botones al encender. */
@Composable
fun compatPaletteTitle(id: Int): String {
    val names = stringArrayResource(R.array.compat_palette_names)
    return names.getOrElse(id) { names[0] }
}

/** Opciones 0..12 para un selector de paleta. */
@Composable
fun compatPaletteOptions(): List<Pair<Int, String>> =
    (0..MAX_COMPAT_PALETTE).map { it to compatPaletteTitle(it) }

/** Ajustes › Emulación: color en juegos de Game Boy y su paleta. Cada juego puede personalizarlo en su detalle. */
@Composable
fun EmulationSettingsScreen(repository: GameplaySettingsRepository, onBack: () -> Unit) {
    val data by repository.state.collectAsStateWithLifecycle()
    val failure by repository.persistFailure.collectAsStateWithLifecycle()
    // N8: estado de `gba_bios.bin` en la raíz de la carpeta (= iOS Ajustes › Emulación › BIOS), leído fuera del hilo principal.
    val context = androidx.compose.ui.platform.LocalContext.current
    var bios by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<GbaBiosStatus?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        bios = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.joelbermudez.pocketgb.library.GbaBiosSource.status(context)
        }
    }
    EmulationSettingsContent(data, repository::update, onBack, warning = persistWarning(failure != null), biosStatus = bios)
}

@Composable
fun EmulationSettingsContent(
    data: GameplaySettingsData,
    onUpdate: ((GameplaySettingsData) -> GameplaySettingsData) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    warning: String? = null,
    /** N8: estado de la BIOS de GBA de la carpeta; `null` = comprobando. */
    biosStatus: GbaBiosStatus? = null,
) {
    SettingsPage(stringResource(R.string.settings_emulation), onBack, modifier, warning) {
        SettingsGroup(
            header = stringResource(R.string.emulation_header),
            footer = stringResource(R.string.emulation_footer),
        ) {
            SwitchRow(
                title = stringResource(R.string.emulation_color_label),
                checked = data.colorForGameBoy,
                onCheckedChange = { value -> onUpdate { it.copy(colorForGameBoy = value) } },
                tag = "emulation-color",
            )
            DropdownRow(
                title = stringResource(R.string.emulation_palette_label),
                options = compatPaletteOptions(),
                selected = data.compatPalette,
                onSelect = { value -> onUpdate { it.copy(compatPalette = value) } },
                enabled = data.colorForGameBoy,
                tag = "emulation-palette",
            )
        }
        SettingsGroup {
            ValueRow(stringResource(R.string.emulation_gbc_label), stringResource(R.string.emulation_gbc_value))
        }
        SettingsGroup(
            header = stringResource(R.string.n8_emulation_gba_header),
            footer = stringResource(R.string.n8_emulation_gba_footer),
        ) {
            // El estado es una frase: va debajo del título (en una fila «título — valor» el título se aplastaba).
            androidx.compose.material3.ListItem(
                headlineContent = { androidx.compose.material3.Text(stringResource(R.string.n8_game_setting_bios)) },
                supportingContent = {
                    androidx.compose.material3.Text(
                        biosStatus?.let { gbaBiosShortText(it) } ?: stringResource(R.string.n8_bios_checking),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.testTag("emulation-gba-bios"),
            )
        }
    }
}

/** Estado de la BIOS en una fila de valor (= iOS `BIOSFile.Status.settingsText`). */
@Composable
fun gbaBiosShortText(status: GbaBiosStatus): String = stringResource(
    when (status) {
        GbaBiosStatus.ABSENT -> R.string.n8_bios_short_absent
        GbaBiosStatus.VALID -> R.string.n8_bios_short_valid
        GbaBiosStatus.INVALID -> R.string.n8_bios_short_invalid
    },
)
