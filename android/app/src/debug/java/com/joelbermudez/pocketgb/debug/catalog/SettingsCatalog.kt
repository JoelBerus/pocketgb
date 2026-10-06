package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.runtime.Composable
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.library.ByteFormat
import com.joelbermudez.pocketgb.settings.StorageUsage
import com.joelbermudez.pocketgb.ui.settings.AudioSettingsContent
import com.joelbermudez.pocketgb.ui.settings.ControlsSettingsContent
import com.joelbermudez.pocketgb.ui.settings.DisplaySettingsContent
import com.joelbermudez.pocketgb.ui.settings.EmulationSettingsContent
import com.joelbermudez.pocketgb.ui.settings.LicensesScreen
import com.joelbermudez.pocketgb.ui.settings.StorageSettingsContent

/** Pantallas de Ajustes de A6 (L1): cada una con datos de ajustes construidos desde los argumentos. */
internal val settingsCatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    put("settings-controls") { i -> ControlsSettingsContent(i.gameplaySettings(), onUpdate = {}, onBack = {}) }
    put("settings-display") { i -> DisplaySettingsContent(i.gameplaySettings(), onUpdate = {}, onBack = {}) }
    put("settings-emulation") { i -> EmulationSettingsContent(i.gameplaySettings(), onUpdate = {}, onBack = {}) }
    put("settings-audio") { i -> AudioSettingsContent(i.gameplaySettings(), onUpdate = {}, onBack = {}) }
    put("settings-storage") {
        StorageSettingsContent(
            usage = StorageUsage(saves = 96L * 1024, states = 3_400_000L, artwork = 410_000L),
            formatSize = { ByteFormat.format(it) },
            onClearArtwork = {},
            onBack = {},
        )
    }
    put("settings-licenses") { LicensesScreen(onBack = {}) }
}
