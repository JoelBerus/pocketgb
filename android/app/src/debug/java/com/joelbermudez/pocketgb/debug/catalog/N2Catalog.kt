package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.LocalPreviewDpadMask
import com.joelbermudez.pocketgb.ui.settings.ControlsSettingsContent
import com.joelbermudez.pocketgb.ui.settings.DiagonalsGroup
import com.joelbermudez.pocketgb.ui.settings.components.SettingsPage

private typealias N2Screen = @Composable (DebugIntent) -> Unit

/**
 * N2 · controles: la cruz y las flechas separadas con ↑ (o una diagonal) pulsado, con distintas separaciones, sobre un
 * fotograma blanco, en contraste alto, el editor con la separación y Ajustes › Controles con las diagonales. La
 * pulsación (`pressed=up`, `pressed=upright`…) solo se dibuja: el catálogo no toca la pantalla.
 */
internal val n2CatalogScreens: Map<String, N2Screen> = buildMap {
    fun gameplay(id: String, editing: Boolean = false, selected: ControlId? = null) = put(id) { i ->
        GameplayFrame {
            CompositionLocalProvider(LocalPreviewDpadMask provides i.pressedDpad) {
                GameplayCatalogScreen(i.gameplaySettings(), i.rom, editing = editing, initialSelected = selected)
            }
        }
    }
    gameplay("n2-cross-up")
    gameplay("n2-cross-up-landscape")
    gameplay("n2-cross-diagonal")
    gameplay("n2-cross-up-light-frame")
    gameplay("n2-cross-up-high-contrast")
    gameplay("n2-cross-up-opacity30")
    gameplay("n2-arrows-up")
    gameplay("n2-arrows-up-landscape")
    gameplay("n2-arrows-diagonal")
    gameplay("n2-arrows-up-light-frame")
    gameplay("n2-arrows-up-high-contrast")
    gameplay("n2-arrows-up-opacity30")
    gameplay("n2-arrows-separation-min")
    gameplay("n2-arrows-separation-max")
    gameplay("n2-arrows-separation-max-landscape")
    gameplay("n2-editor-arrows", editing = true, selected = ControlId.DPAD)
    gameplay("n2-editor-arrows-landscape", editing = true, selected = ControlId.DPAD)
    gameplay("n2-editor-cross", editing = true, selected = ControlId.DPAD)

    put("n2-settings-controls") { i -> ControlsSettingsContent(i.gameplaySettings(), onUpdate = {}, onBack = {}) }
    put("n2-settings-controls-scrolled") { i -> ControlsSettingsContent(i.gameplaySettings(), onUpdate = {}, onBack = {}) }
    put("n2-settings-controls-ax5") { i -> ControlsSettingsContent(i.gameplaySettings(), onUpdate = {}, onBack = {}) }
    // Solo el grupo «Diagonales» (la página entera no deja ver sus filas con la fuente al 200 % sin desplazar varias veces).
    put("n2-settings-diagonals-ax5") { i ->
        SettingsPage("Controles", onBack = {}) { DiagonalsGroup(i.gameplaySettings(), onUpdate = {}) }
    }
}
