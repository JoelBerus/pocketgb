package com.joelbermudez.pocketgb.ui.gameplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.input.GameControlsOverlay
import com.joelbermudez.pocketgb.video.GameSurface

@Composable
fun GameplayScreen(
    session: EmulatorSession,
    modifier: Modifier = Modifier,
) {
    var speed by remember { mutableIntStateOf(session.speed) }
    val sessionState by session.state.collectAsStateWithLifecycle()
    val pause = {
        if (session.state.value == SessionState.Running) session.pause()
    }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            GameSurface(session, Modifier.fillMaxSize().testTag("gameplay-surface"))
            GameControlsOverlay(session, pause, Modifier.fillMaxSize().testTag("game-controls"))
        } else {
            Column(Modifier.fillMaxSize()) {
                GameSurface(
                    session,
                    Modifier.fillMaxWidth().aspectRatio(10f / 9f).testTag("gameplay-surface"),
                )
                GameControlsOverlay(
                    session,
                    pause,
                    Modifier.fillMaxWidth().weight(1f).testTag("game-controls"),
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(1, 2, 4).forEach { factor ->
                FilterChip(
                    selected = speed == factor,
                    onClick = {
                        session.setSpeed(factor)
                        speed = factor
                    },
                    label = { Text("×$factor") },
                )
            }
        }
        if (sessionState == SessionState.Paused) {
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
