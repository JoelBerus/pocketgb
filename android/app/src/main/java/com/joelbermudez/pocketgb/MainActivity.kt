package com.joelbermudez.pocketgb

import android.os.Build
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.WindowManager
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

    /** N7b: documento recibido por «Abrir con» o «Compartir» (se procesa una vez). */
    private val incoming = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    private fun incomingUri(intent: android.content.Intent?): android.net.Uri? = when (intent?.action) {
        android.content.Intent.ACTION_VIEW -> intent.data
        android.content.Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        }
        else -> null
    }?.takeIf { it.scheme == "content" }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        incomingUri(intent)?.let { incoming.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) incoming.value = incomingUri(intent)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // A7 R15: la imagen puede invadir el recorte del borde corto; los controles no (área segura de la partida).
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
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
                        incomingUri = incoming.value,
                        onIncomingHandled = { incoming.value = null },
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

    // Memoria baja (A7 R13). En segundo plano (UI_HIDDEN o peor) la partida se pausa por el mismo camino que ON_PAUSE;
    // en primer plano solo se libera la caché de portadas. Nunca toca la ruta de guardado.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) com.joelbermudez.pocketgb.library.artwork.CoverRepository.shared(applicationContext).trimMemory()
        gameplay.onTrimMemory(level)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        com.joelbermudez.pocketgb.library.artwork.CoverRepository.shared(applicationContext).trimMemory()
        gameplay.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }
}
