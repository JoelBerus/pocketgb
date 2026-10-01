package com.joelbermudez.pocketgb.input

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.joelbermudez.pocketgb.emulator.EmulatorSession

@Composable
fun GameControlsOverlay(
    session: EmulatorSession,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
) {
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer.toArgb()
    val background = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f).toArgb()
    val pressed = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f).toArgb()
    AndroidView(
        factory = { context ->
            GameControlsView(
                context = context,
                onMaskChanged = session::setTouchButtons,
                onMenu = onMenu,
            )
        },
        modifier = modifier.semantics {
            contentDescription = "Controles del juego: cruceta, A, B, Start, Select y Menú"
        },
        update = { view ->
            view.onMaskChanged = session::setTouchButtons
            view.onMenu = onMenu
            view.hapticsEnabled = hapticsEnabled
            view.foregroundColor = foreground
            view.controlBackgroundColor = background
            view.pressedColor = pressed
        },
        onRelease = GameControlsView::release,
    )
}
