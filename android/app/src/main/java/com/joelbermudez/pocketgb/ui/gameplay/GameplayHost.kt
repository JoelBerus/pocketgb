package com.joelbermudez.pocketgb.ui.gameplay

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.app.SessionLifecycleObserver
import com.joelbermudez.pocketgb.audio.AudioFocusController
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.game.GameMenu
import com.joelbermudez.pocketgb.game.GameSession
import com.joelbermudez.pocketgb.game.GameplayViewModel

/**
 * Raíz del juego: pinta la partida a pantalla completa si hay sesión (sin ruta en el back stack), o
 * [content] si no; encima, la cortina de apertura y los diálogos. La sesión vive en el ViewModel.
 */
@Composable
fun GameplayRoot(viewModel: GameplayViewModel, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val game by viewModel.game.collectAsStateWithLifecycle()
    val opening by viewModel.opening.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        val current = game
        if (current != null) GameplayHost(viewModel, current) else content()
        if (opening) OpeningOverlay()
        GameDialogs(viewModel)
    }
}

/** Partida en curso: gameplay, ciclo de vida, foco de audio, atrás, menú de pausa, estados y avisos. */
@Composable
fun GameplayHost(viewModel: GameplayViewModel, game: GameSession, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val states by viewModel.states.collectAsStateWithLifecycle()
    val sessionState by game.state.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snackbar = remember { SnackbarHostState() }

    val observer = remember(game) { SessionLifecycleObserver(game) }
    DisposableEffect(game, lifecycle) {
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val audioFocus = remember(game, context) { AudioFocusController(context) { viewModel.onBackground() } }
    DisposableEffect(audioFocus) {
        audioFocus.request()
        onDispose { audioFocus.close() }
    }

    // Una pausa sin menú (segundo plano, foco de audio, rotación) deja el menú abierto al volver: el juego
    // queda en pausa y Joel decide cuándo continuar.
    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.Paused) viewModel.revealPauseMenu()
    }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { snackbar.showSnackbar(noticeText(context, it)) }
    }

    BackHandler(enabled = menu == GameMenu.None && dialog == null) { viewModel.showPauseMenu() }

    Box(modifier.fillMaxSize()) {
        GameplayScreen(game.session, onMenu = viewModel::showPauseMenu, showPausedOverlay = false)
        if (menu != GameMenu.States) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        when (menu) {
            GameMenu.None -> Unit
            GameMenu.Pause -> PauseSheet(
                landscape = landscape,
                busy = busy,
                onContinue = viewModel::continueGame,
                onStates = viewModel::openStates,
                onExit = { viewModel.exit(force = false) },
            )
            GameMenu.States -> StatesSheet(
                landscape = landscape,
                ui = states,
                snackbar = snackbar,
                onBack = viewModel::closeStates,
                onSave = viewModel::saveState,
                onLoad = viewModel::loadState,
                onDelete = viewModel::deleteState,
            )
        }
    }
}
