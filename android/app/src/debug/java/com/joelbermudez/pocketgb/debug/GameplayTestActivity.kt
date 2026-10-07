package com.joelbermudez.pocketgb.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import com.joelbermudez.pocketgb.input.GamepadConnection
import com.joelbermudez.pocketgb.input.GamepadRouter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.joelbermudez.pocketgb.game.GameplayViewModel
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.gameplay.GameplayRoot
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme

/** Configuración del anfitrión de pruebas: quien lo lance define cómo se crea el ViewModel (sobrevive a `recreate()`). */
object GameplayTestConfig {
    @Volatile var factory: (() -> GameplayViewModel)? = null

    /** Repositorio de ajustes de la prueba (con un archivo temporal); `null` usa el de la app. */
    @Volatile var settings: GameplaySettingsRepository? = null

    /** Mando «conectado» inyectado; `null` usa el monitor real. */
    @Volatile var gamepad: GamepadConnection? = null

    /** Tema de la app bajo el juego: las pruebas lo ponen en claro para comprobar que el juego sigue oscuro (K4). */
    @Volatile var appearance: AppearanceState = AppearanceState.DEFAULT
}

/**
 * Solo Debug (no existe en Release): anfitrión de las pruebas instrumentadas del juego. Un APK de test no
 * puede declarar actividades que resuelvan en el proceso de la app, así que vive aquí.
 */
class GameplayTestActivity : ComponentActivity() {
    private val vm: GameplayViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(GameplayTestConfig.factory)() as T
        }
    }

    val viewModel: GameplayViewModel get() = vm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketGBTheme(appearance = GameplayTestConfig.appearance) {
                GameplayRoot(
                    vm,
                    settings = GameplayTestConfig.settings ?: GameplaySettingsRepository.shared(this),
                    gamepad = GameplayTestConfig.gamepad,
                ) {
                    Text("sin juego", Modifier.testTag("no-game"))
                }
            }
        }
    }

    // `ComponentActivity.dispatchKeyEvent` está marcado RestrictedApi (androidx.core), pero es el gancho oficial de la vista.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = GamepadRouter.dispatchKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        GamepadRouter.dispatchMotion(event) || super.dispatchGenericMotionEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        GamepadRouter.onWindowFocusChanged(hasFocus)
    }
}
