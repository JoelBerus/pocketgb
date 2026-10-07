package com.joelbermudez.pocketgb.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.emulator.CoreBridge
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.video.GameSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeVideoScreen() {
    val session = remember { EmulatorSession() }
    DisposableEffect(session) {
        session.load(DebugSyntheticRom.create())
        session.start()
        onDispose { session.close() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Vídeo nativo") }) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CoreBridge.SCREEN_WIDTH.toFloat() / CoreBridge.SCREEN_HEIGHT)
                    .background(Color.Black, MaterialTheme.shapes.large)
                    .padding(8.dp),
            ) {
                GameSurface(
                    session = session,
                    modifier = Modifier.fillMaxSize().testTag("native-video-surface"),
                )
            }
        }
    }
}
