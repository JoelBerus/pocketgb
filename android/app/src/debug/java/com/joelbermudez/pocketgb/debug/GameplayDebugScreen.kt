package com.joelbermudez.pocketgb.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.joelbermudez.pocketgb.app.SessionLifecycleObserver
import com.joelbermudez.pocketgb.audio.AudioFocusController
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.ui.gameplay.GameplayScreen

@Composable
internal fun GameplayDebugScreen(initialSpeed: Int = 1) {
    val session = remember(initialSpeed) {
        EmulatorSession().apply { setSpeed(initialSpeed) }
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleObserver = remember(session) { SessionLifecycleObserver(session) }
    val audioFocus = remember(session, context) {
        AudioFocusController(context) {
            if (session.state.value == SessionState.Running) session.pause()
        }
    }
    DisposableEffect(session, lifecycle) {
        audioFocus.request()
        session.load(DebugSyntheticRom.create())
        session.start()
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            audioFocus.close()
            session.close()
        }
    }
    GameplayScreen(session)
}
