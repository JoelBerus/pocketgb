package com.joelbermudez.pocketgb.ui.gameplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.ScaleMode
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.ControlLayout
import com.joelbermudez.pocketgb.input.ControlsEditorBinding
import com.joelbermudez.pocketgb.input.ControlsOrientation
import com.joelbermudez.pocketgb.input.GameControlsOverlay
import com.joelbermudez.pocketgb.input.SafeInsets
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.video.GameSurface

/** Escalado (K3): vertical siempre Llenar; horizontal Entero salvo que el ajuste lo desactive. */
fun scaleModeFor(landscape: Boolean, settings: GameplaySettingsData): ScaleMode =
    if (landscape && settings.integerScaleLandscape) ScaleMode.INTEGER else ScaleMode.FILL

@Composable
fun GameplayScreen(
    session: EmulatorSession,
    modifier: Modifier = Modifier,
    /** Con valor, el botón de pausa del HUD y el gesto de pausa delegan en el menú de pausa de la partida. */
    onMenu: (() -> Unit)? = null,
    /** El catálogo debug usa su propio aviso de pausa; la partida real usa el menú de pausa. */
    showPausedOverlay: Boolean = true,
    settings: GameplaySettingsData = GameplaySettingsData(),
    /** Editor de controles abierto (juego en pausa): el HUD se oculta y los controles se arrastran. */
    editing: Boolean = false,
    onEditingDone: () -> Unit = {},
    /** Guarda un cambio de ajustes (el editor persiste con esto en la orientación actual). */
    onSettingsChange: ((GameplaySettingsData) -> GameplaySettingsData) -> Unit = {},
) {
    var speed by remember { mutableIntStateOf(session.speed) }
    val sessionState by session.state.collectAsStateWithLifecycle()
    var selected by remember(editing) { mutableStateOf<ControlId?>(null) }
    val pause = {
        if (onMenu != null) onMenu()
        else if (session.state.value == SessionState.Running) session.pause()
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val safeLeft = safe.getLeft(density, direction)
    val safeRight = safe.getRight(density, direction)
    val safeTop = safe.getTop(density)
    val safeBottom = safe.getBottom(density)

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val orientation = if (landscape) ControlsOrientation.LANDSCAPE else ControlsOrientation.PORTRAIT
        val scaleMode = scaleModeFor(landscape, settings)
        val editor = if (editing) {
            ControlsEditorBinding(
                selected = selected,
                onSelect = { selected = it },
                onCommit = { id, point -> onSettingsChange { it.move(orientation, id, point) } },
            )
        } else {
            null
        }
        if (landscape) {
            GameSurface(session, Modifier.fillMaxSize().testTag("gameplay-surface"), scaleMode)
            // Horizontal: los controles quedan dentro del área segura (cutout), la imagen puede ocupar todo.
            GameControlsOverlay(
                session, pause, Modifier.fillMaxSize().testTag("game-controls"),
                settings = settings, orientation = orientation,
                safeInsets = SafeInsets(safeLeft, safeTop, safeRight, safeBottom),
                editor = editor,
            )
        } else {
            // Vertical: la imagen va bajo el cutout (no dentro de él) y a todo el ancho.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))) {
                GameSurface(
                    session,
                    Modifier.fillMaxWidth().aspectRatio(10f / 9f).testTag("gameplay-surface"),
                    scaleMode,
                )
                GameControlsOverlay(
                    session,
                    pause,
                    Modifier.fillMaxWidth().weight(1f).testTag("game-controls"),
                    settings = settings, orientation = orientation,
                    safeInsets = SafeInsets(safeLeft, 0, safeRight, safeBottom),
                    editor = editor,
                )
            }
        }
        if (editing) {
            ControlsEditorBar(
                orientation = orientation,
                selected = selected,
                selectedScale = selected?.let { ControlLayout.from(settings.layout(orientation), orientation).scale(it) } ?: 1f,
                onReset = { onSettingsChange { it.resetLayout(orientation) } },
                onDone = onEditingDone,
                onSmaller = { selected?.let { id -> onSettingsChange { it.resize(orientation, id, -0.1f) } } },
                onLarger = { selected?.let { id -> onSettingsChange { it.resize(orientation, id, 0.1f) } } },
                modifier = Modifier
                    .align(if (landscape) Alignment.Center else Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            )
        } else {
            GameplayHud(
                speed = speed,
                onPause = pause,
                onCycleSpeed = {
                    val next = SpeedCycle.next(speed)
                    session.setSpeed(next)
                    speed = next
                },
                haptics = settings.haptics,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(
                        if (landscape) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)
                        else WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.End),
                    )
                    .padding(8.dp),
            )
        }
        if (showPausedOverlay && sessionState == SessionState.Paused) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Juego en pausa", style = MaterialTheme.typography.titleLarge)
                Button(onClick = {
                    if (session.state.value == SessionState.Paused) session.resume()
                }) { Text("Continuar") }
            }
        }
    }
}
