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
import com.joelbermudez.pocketgb.game.GameSession
import com.joelbermudez.pocketgb.saves.StateStore
import java.io.File
import com.joelbermudez.pocketgb.ui.gameplay.GameplayScreen

@Composable
internal fun GameplayDebugScreen(initialSpeed: Int = 1) {
    val context = LocalContext.current
    val game = remember(initialSpeed) {
        val session = EmulatorSession().apply { setSpeed(initialSpeed) }
        val info = session.load(DebugSyntheticRom.create())
        GameSession(session, info, StateStore(File(context.cacheDir, "debug-states")))
    }
    val session = game.session
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleObserver = remember(game) { SessionLifecycleObserver(game) }
    val audioFocus = remember(session, context) {
        AudioFocusController(context) {
            if (session.state.value == SessionState.Running) session.pause()
        }
    }
    DisposableEffect(game, lifecycle) {
        audioFocus.request()
        game.start()
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            audioFocus.close()
            game.close()
        }
    }
    GameplayScreen(session)
}
