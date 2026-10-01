package com.joelbermudez.pocketgb

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.app.PocketGBApp
import com.joelbermudez.pocketgb.settings.AppearanceRepository
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.appearanceDataStore
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        val appearanceRepository = AppearanceRepository(applicationContext.appearanceDataStore)
        setContent {
            if (!buildVariantContent(intent)) {
                val appearance by appearanceRepository.state.collectAsStateWithLifecycle(
                    initialValue = AppearanceState.DEFAULT,
                )
                PocketGBTheme(appearance = appearance) {
                    PocketGBApp(
                        appearance = appearance,
                        appearanceRepository = appearanceRepository,
                    )
                }
            }
        }
    }
}
