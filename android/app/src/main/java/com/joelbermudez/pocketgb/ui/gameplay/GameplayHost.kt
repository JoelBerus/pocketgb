package com.joelbermudez.pocketgb.ui.gameplay

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
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
import com.joelbermudez.pocketgb.input.GamepadConnection
import com.joelbermudez.pocketgb.input.GamepadMonitor
import com.joelbermudez.pocketgb.input.GamepadRouter
import com.joelbermudez.pocketgb.input.GamepadSink
import com.joelbermudez.pocketgb.input.GamepadState
import com.joelbermudez.pocketgb.input.PadAction
import com.joelbermudez.pocketgb.input.PadOutput
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.settings.compatPaletteChanges
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag

/**
 * Raíz del juego: pinta la partida a pantalla completa si hay sesión (sin ruta en el back stack), o
 * [content] si no; encima, la cortina de apertura y los diálogos. La sesión vive en el ViewModel.
 */
@Composable
fun GameplayRoot(
    viewModel: GameplayViewModel,
    modifier: Modifier = Modifier,
    settings: GameplaySettingsRepository = GameplaySettingsRepository.shared(LocalContext.current),
    /** Mando conectado; `null` = el monitor real del sistema (las pruebas inyectan uno). */
    gamepad: GamepadConnection? = null,
    content: @Composable () -> Unit,
) {
    val game by viewModel.game.collectAsStateWithLifecycle()
    val opening by viewModel.opening.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        val current = game
        // El juego siempre es oscuro (K4): superficie, HUD, hojas y diálogos, sea cual sea el tema de la app.
        if (current != null) GameplayTheme { GameplayHost(viewModel, current, settings = settings, gamepad = gamepad) } else content()
        if (opening) OpeningOverlay()
        GameplayTheme { GameDialogs(viewModel) }
    }
}

@Composable
private fun GameplayTheme(content: @Composable () -> Unit) =
    PocketGBTheme(forceDark = true, dynamicColor = false, content = content)

/** Partida en curso: gameplay, ciclo de vida, foco de audio, atrás, menú de pausa, estados y avisos. */
@Composable
fun GameplayHost(
    viewModel: GameplayViewModel,
    game: GameSession,
    modifier: Modifier = Modifier,
    settings: GameplaySettingsRepository = GameplaySettingsRepository.shared(LocalContext.current),
    gamepad: GamepadConnection? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val states by viewModel.states.collectAsStateWithLifecycle()
    val sessionState by game.state.collectAsStateWithLifecycle()
    val saveProblem by game.saveProblem.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snackbar = remember { SnackbarHostState() }
    val prefs by settings.state.collectAsStateWithLifecycle()

    ImmersiveMode()
    val padConnection = rememberGamepadConnection(gamepad)
    val padConnected by padConnection.connected.collectAsStateWithLifecycle()
    // El primer evento de mando también oculta los controles aunque el sistema no haya avisado (R5).
    var padSeen by remember { mutableStateOf(false) }
    LaunchedEffect(padConnected) { if (!padConnected) padSeen = false }
    val speedRequests = remember(game) { MutableSharedFlow<Unit>(extraBufferCapacity = 4) }
    val padEnabled = menu == GameMenu.None && dialog == null
    DisposableEffect(game, padEnabled, prefs.controllerMapping) {
        if (!padEnabled) return@DisposableEffect onDispose { }
        val state = GamepadState(prefs.controllerMapping)
        fun apply(out: PadOutput) {
            game.session.setPhysicalButtons(out.mask)
            for (action in out.actions) when (action) {
                PadAction.MENU -> viewModel.showPauseMenu()
                PadAction.FAST_FORWARD -> speedRequests.tryEmit(Unit)
                else -> Unit
            }
        }
        val unregister = GamepadRouter.register(object : GamepadSink {
            override fun onKey(keyCode: Int, down: Boolean): Boolean {
                padSeen = true
                apply(state.onKey(keyCode, down))
                return true
            }

            override fun onAxes(hatX: Float, hatY: Float, x: Float, y: Float): Boolean {
                padSeen = true
                apply(state.onAxes(hatX, hatY, x, y))
                return true
            }

            override fun onFocusLost() = apply(state.reset())
        })
        onDispose {
            unregister()
            // Nunca un botón físico «pegado» al cerrar una hoja, perder el foco o salir de la partida.
            game.session.setPhysicalButtons(0)
        }
    }
    // Desconexión: máscara a 0 aunque no llegue el «soltar».
    LaunchedEffect(padConnected, game) { if (!padConnected) game.session.setPhysicalButtons(0) }
    val hideTouch = (padConnected || padSeen) && !prefs.showTouchControlsWithController
    // Volumen y paleta CGB en caliente (K2, K8): la paleta solo si la sesión ya está en compatibilidad CGB; el modelo
    // nunca cambia con la sesión abierta. La escala la aplica GameSurface (K3).
    LaunchedEffect(game, prefs.volume) { game.setVolume(prefs.volume) }
    LaunchedEffect(game) {
        settings.state.compatPaletteChanges(game.fingerprint)
            .collect { palette -> runCatching { game.setCompatPalette(palette) } }
    }

    val observer = remember(game) { SessionLifecycleObserver(game, viewModel::onFlushResult) }
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
    BackHandler(enabled = menu == GameMenu.Editor) { viewModel.closeControlsEditor() }

    Box(modifier.fillMaxSize()) {
        GameplayScreen(
            game.session,
            onMenu = viewModel::showPauseMenu,
            showPausedOverlay = false,
            settings = touchSettingsFor(prefs, hideTouch),
            speedCycleRequests = speedRequests,
            editing = menu == GameMenu.Editor,
            onEditingDone = viewModel::closeControlsEditor,
            onSettingsChange = { change -> settings.update(change) },
        )
        // Último fotograma atenuado detrás de las hojas de pausa y de estados.
        if (menu == GameMenu.Pause || menu == GameMenu.States) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).testTag("pause-dim"))
        }
        SaveProblemBanner(
            saveProblem,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp),
        )
        if (menu != GameMenu.States) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        when (menu) {
            GameMenu.None, GameMenu.Editor -> Unit
            GameMenu.Pause -> PauseSheet(
                landscape = landscape,
                busy = busy,
                title = game.info.title,
                onContinue = viewModel::continueGame,
                onStates = viewModel::openStates,
                onCustomize = viewModel::openControlsEditor,
                onExit = { viewModel.exit(force = false) },
            )
            GameMenu.States -> StatesSheet(
                landscape = landscape,
                ui = states,
                snackbar = snackbar,
                onBack = viewModel::closeStates,
                onSave = viewModel::saveState,
                onLoad = { slot, saveCurrent -> viewModel.loadState(slot, saveCurrent) },
                onDelete = viewModel::deleteState,
            )
        }
    }
}

/** Con mando (R5) los controles táctiles se ocultan salvo que el ajuste los mantenga; el catálogo Debug usa lo mismo. */
internal fun touchSettingsFor(prefs: GameplaySettingsData, hideTouch: Boolean): GameplaySettingsData =
    if (hideTouch) prefs.copy(visibility = ControlsVisibility.HIDDEN) else prefs

/** La conexión inyectada, o un [GamepadMonitor] real mientras el juego está compuesto. */
@Composable
private fun rememberGamepadConnection(injected: GamepadConnection?): GamepadConnection {
    if (injected != null) return injected
    val context = LocalContext.current
    val monitor = remember(context) { GamepadMonitor(context) }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }
    return monitor
}
