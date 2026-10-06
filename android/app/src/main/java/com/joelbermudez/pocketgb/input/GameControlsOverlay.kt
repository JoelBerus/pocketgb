package com.joelbermudez.pocketgb.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.GameplaySettingsData

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
    val description = stringResource(R.string.controls_content_description)
    AndroidView(
        factory = { context ->
            GameControlsView(
                context = context,
                onMaskChanged = session::setTouchButtons,
                onMenu = onMenu,
            )
        },
        modifier = modifier.semantics { contentDescription = description },
        update = { view ->
            view.onMaskChanged = session::setTouchButtons
            view.onMenu = onMenu
            view.hapticsEnabled = settings.haptics
            view.renderOptions = ControlsRenderOptions.from(settings)
            view.controlsVisibility = if (editor != null) ControlsVisibility.ALWAYS else settings.visibility
            view.sizeScale = settings.sizeScale
            view.safeInsets = safeInsets
            view.orientationOverride = orientation
            view.controlLayout = orientation?.let { ControlLayout.from(settings.layout(it), it) }
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
