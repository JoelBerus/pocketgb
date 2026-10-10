package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.joelbermudez.pocketgb.audio.AudioFocusController
import com.joelbermudez.pocketgb.app.SessionLifecycleObserver
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.debug.DebugSyntheticRom
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.game.GameNotice
import com.joelbermudez.pocketgb.game.GameSession
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.ControlsOrientation
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.gameplay.GameplayScreen
import com.joelbermudez.pocketgb.ui.gameplay.ImmersiveMode
import com.joelbermudez.pocketgb.ui.gameplay.noticeText
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import java.io.File

/** El juego es siempre oscuro (K4) y a pantalla completa (K5): lo mismo que `GameplayRoot`, sea cual sea el tema. */
@Composable
internal fun GameplayFrame(content: @Composable () -> Unit) {
    PocketGBTheme(forceDark = true, dynamicColor = false) {
        ImmersiveMode()
        content()
    }
}

/**
 * Partida sintética real (núcleo nativo + ROM sintética) con los ajustes de [initial]; con [editing] el editor de
 * controles queda abierto y sus cambios se aplican sobre los ajustes locales. [notice] pinta el aviso de la partida.
 */
@Composable
internal fun GameplayCatalogScreen(
    initial: GameplaySettingsData,
    rom: String = "stripes",
    initialSpeed: Int = 1,
    editing: Boolean = false,
    initialSelected: ControlId? = null,
    notice: GameNotice? = null,
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(initial) }
    val game = remember(initialSpeed, rom) {
        if (rom == "gba") {
            // N8: ROM de GBA sintética (modo 3, degradado): imagen 3:2 y controles con L y R.
            val session = EmulatorSession(com.joelbermudez.pocketgb.emulator.Console.GBA).apply { setSpeed(initialSpeed) }
            val info = session.loadGba(com.joelbermudez.pocketgb.debug.DebugSyntheticGbaRom.gradient())
            return@remember GameSession(session, info, StateStore(File(context.cacheDir, "debug-states-gba")))
        }
        val session = EmulatorSession().apply { setSpeed(initialSpeed) }
        val bytes = when (rom) {
            "light" -> DebugSyntheticRom.create(light = true)
            "damaged" -> DebugSyntheticRom.create(damagedHeader = true)
            else -> DebugSyntheticRom.create()
        }
        val info = session.load(bytes)
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
    Box(Modifier.fillMaxSize()) {
        GameplayScreen(
            session,
            settings = settings,
            editing = editing,
            initialSelected = initialSelected,
            onSettingsChange = { change -> settings = change(settings) },
        )
        if (notice != null) {
            Snackbar(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) {
                Text(noticeText(context, notice))
            }
        }
    }
}

private fun withScale(base: GameplaySettingsData, orientation: String, id: ControlId, scale: Float): GameplaySettingsData {
    val o = if (orientation == "landscape") ControlsOrientation.LANDSCAPE else ControlsOrientation.PORTRAIT
    return base.resize(o, id, scale - 1f)
}

private fun controlOf(name: String?): ControlId? = ControlId.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

/** Pantallas de juego de A6 (L2 y L4): siempre oscuras; la orientación la aplica el script girando el emulador. */
internal val gameplayCatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    fun screen(id: String, content: @Composable (DebugIntent) -> Unit) = put(id) { i -> GameplayFrame { content(i) } }

    screen("gameplay-landscape") { i -> GameplayCatalogScreen(i.gameplaySettings(), i.rom) }
    screen("gameplay-landscape-clear") { i -> GameplayCatalogScreen(i.gameplaySettings(), i.rom) }
    screen("gameplay-landscape-hidden") { i -> GameplayCatalogScreen(i.gameplaySettings(), i.rom) }
    screen("gameplay-portrait-arrows") { i -> GameplayCatalogScreen(i.gameplaySettings(), i.rom) }
    screen("gameplay-landscape-arrows") { i -> GameplayCatalogScreen(i.gameplaySettings(), i.rom) }
    screen("gameplay-fill") { i -> GameplayCatalogScreen(i.gameplaySettings(), i.rom) }
    screen("customize-controls-portrait") { i -> GameplayCatalogScreen(i.gameplaySettings(), i.rom, editing = true) }
    screen("customize-controls-landscape") { i -> GameplayCatalogScreen(i.gameplaySettings(), i.rom, editing = true) }
    screen("customize-controls-size") { i ->
        val id = controlOf(i.selected) ?: ControlId.A
        val settings = withScale(i.gameplaySettings(), i.orientation, id, i.scale ?: 1.3f)
        GameplayCatalogScreen(settings, i.rom, editing = true, initialSelected = id)
    }
    screen("gameplay-header-damaged") { i ->
        GameplayCatalogScreen(i.gameplaySettings(), rom = "damaged", notice = GameNotice.HeaderDamaged)
    }
    screen("load-state-confirm") { Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) { MomentsCatalogSheet(false, preview = com.joelbermudez.pocketgb.ui.moments.MomentsPreview.LOAD) } }
    screen("replace-state-confirm") { Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) { MomentsCatalogSheet(false, preview = com.joelbermudez.pocketgb.ui.moments.MomentsPreview.CREATE) } }
}

/** Capturas de estado sintéticas (degradado + rejilla), las mismas en cada ejecución. */
internal object CatalogStates {
    fun thumbnail(seed: Int): ByteArray? {
        val pixels = IntArray(160 * 144) { i ->
            val x = i % 160
            val y = i / 160
            val shade = ((x + y * 2 + seed * 40) % 256)
            val grid = if (x % 16 == 0 || y % 16 == 0) 0x40 else 0
            0xFF000000.toInt() or ((shade xor grid) shl 16) or (((255 - shade) / 2) shl 8) or (seed * 50 % 256)
        }
        return com.joelbermudez.pocketgb.saves.FramePng.encode(pixels)
    }
}
