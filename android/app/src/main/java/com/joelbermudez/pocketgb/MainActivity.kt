package com.joelbermudez.pocketgb

import android.os.Build
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import com.joelbermudez.pocketgb.input.GamepadRouter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.game.GameplayViewModel
import com.joelbermudez.pocketgb.game.GameplayViewModelFactory
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.LibraryViewModelFactory
import com.joelbermudez.pocketgb.app.PocketGBApp
import com.joelbermudez.pocketgb.settings.AppearanceRepository
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.settings.appearanceDataStore
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme

class MainActivity : ComponentActivity() {
    // Solo se crea (y escanea) fuera del catálogo debug, que usa datos sintéticos.
    private val library: LibraryViewModel by viewModels { LibraryViewModelFactory(applicationContext) }

    // Dueño de la partida abierta: sobrevive a la rotación; la sesión nunca vive en un `remember`.
    private val gameplay: GameplayViewModel by viewModels { GameplayViewModelFactory(applicationContext, library) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        val appearanceRepository = AppearanceRepository(applicationContext.appearanceDataStore)
        val gameplaySettings = GameplaySettingsRepository.shared(applicationContext)
        setContent {
            if (!buildVariantContent(intent)) {
                val appearance by appearanceRepository.state.collectAsStateWithLifecycle(
                    initialValue = AppearanceState.DEFAULT,
                )
                // Cada vuelta a primer plano reescanea la biblioteca (SPEC §6).
                LifecycleEventEffect(Lifecycle.Event.ON_START) { library.rescan() }
                // Reintenta las preferencias cuya escritura falló, antes de que el proceso pueda morir.
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { library.retryPendingWrites() }
                PocketGBTheme(appearance = appearance) {
                    PocketGBApp(
                        appearance = appearance,
                        appearanceRepository = appearanceRepository,
                        library = library,
                        gameplay = gameplay,
                        gameplaySettings = gameplaySettings,
                    )
                }
            }
        }
    }

    // El mando llega al juego antes que a Compose (la cruceta no mueve el foco durante la partida).
    // `ComponentActivity.dispatchKeyEvent` está marcado RestrictedApi (androidx.core), pero es el gancho oficial de la vista.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = GamepadRouter.dispatchKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        GamepadRouter.dispatchMotion(event) || super.dispatchGenericMotionEvent(event)

    // Sin foco de ventana no se queda ningún botón físico pulsado.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        GamepadRouter.onWindowFocusChanged(hasFocus)
    }
}
