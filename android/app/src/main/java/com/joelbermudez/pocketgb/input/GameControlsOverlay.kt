package com.joelbermudez.pocketgb.input

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.a11y.LocalHighContrast
import com.joelbermudez.pocketgb.ui.a11y.LocalReduceMotion

/**
 * Direcciones de la cruceta que se dibujan como pulsadas sin tocar nada: solo lo usa el catálogo debug de capturas
 * (N2); en la app es siempre 0.
 */
val LocalPreviewDpadMask = compositionLocalOf { 0 }

/**
 * Controles táctiles sobre la sesión. [settings] decide opacidad, visibilidad, estilo de cruceta, escala y disposición
 * (todo en caliente); [orientation] y [safeInsets] los fija quien lo coloca. Con [editor] el mismo dibujo sirve de
 * lienzo del editor y no manda nada al juego.
 */
@Composable
fun GameControlsOverlay(
    session: EmulatorSession,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    settings: GameplaySettingsData = GameplaySettingsData(),
    orientation: ControlsOrientation? = null,
    safeInsets: SafeInsets = SafeInsets.NONE,
    editor: ControlsEditorBinding? = null,
) {
    // N8: la consola de la sesión decide si hay L/R y qué disposición (GB o GBA) se usa.
    val console = session.console
    val highContrast = LocalHighContrast.current
    val reduceMotion = LocalReduceMotion.current
    val previewDpadMask = LocalPreviewDpadMask.current
    val colorScheme = MaterialTheme.colorScheme
    val palette = remember(colorScheme) { ControlsPalette.from(colorScheme) }
    AndroidView(
        factory = { context ->
            GameControlsView(
                context = context,
                onMaskChanged = session::setTouchButtons,
                onMenu = onMenu,
            )
        },
        modifier = modifier,
        update = { view ->
            view.onMaskChanged = session::setTouchButtons
            view.onMenu = onMenu
            view.hapticsEnabled = settings.haptics
            view.renderOptions = ControlsRenderOptions.from(settings).copy(highContrast = highContrast, palette = palette)
            view.previewDpadMask = previewDpadMask
            view.reduceMotion = reduceMotion
            view.controlsVisibility = if (editor != null) ControlsVisibility.ALWAYS else settings.visibility
            view.sizeScale = settings.sizeScale
            view.safeInsets = safeInsets
            view.shoulders = console == com.joelbermudez.pocketgb.emulator.Console.GBA
            view.orientationOverride = orientation
            view.controlLayout = orientation?.let { settings.controlLayout(it, console) }
            view.editing = editor != null
            view.selected = editor?.selected
            if (editor != null) {
                view.onEditSelect = editor.onSelect
                view.onEditCommit = editor.onCommit
            }
        },
        onRelease = GameControlsView::release,
    )
}

/** Lo que el editor de controles necesita del lienzo: qué control está elegido y qué hacer al elegir o soltar. */
data class ControlsEditorBinding(
    val selected: ControlId?,
    val onSelect: (ControlId) -> Unit,
    val onCommit: (ControlId, NormalizedPoint) -> Unit,
)
