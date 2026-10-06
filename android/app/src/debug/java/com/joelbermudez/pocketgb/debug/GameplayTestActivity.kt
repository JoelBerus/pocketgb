package com.joelbermudez.pocketgb.debug

import android.os.Bundle
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
import com.joelbermudez.pocketgb.ui.gameplay.GameplayRoot
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme

/** Configuración del anfitrión de pruebas: quien lo lance define cómo se crea el ViewModel (sobrevive a `recreate()`). */
object GameplayTestConfig {
    @Volatile var factory: (() -> GameplayViewModel)? = null
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
            PocketGBTheme(appearance = AppearanceState.DEFAULT) {
                GameplayRoot(vm) { Text("sin juego", Modifier.testTag("no-game")) }
            }
        }
    }
}
