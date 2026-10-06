package com.joelbermudez.pocketgb

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.joelbermudez.pocketgb.debug.DebugCatalog
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.debug.SaveStressScreen
import com.joelbermudez.pocketgb.debug.SaveVerifyScreen
import com.joelbermudez.pocketgb.ui.a11y.LocalAccessibilityOverrides
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme

@Composable
internal fun buildVariantContent(intent: Intent): Boolean {
    // Modos de la prueba de cierre forzado (`tools/android-save-kill-test.sh`): solo existen en Debug.
    when (intent.getStringExtra("debug")) {
        "save-stress" -> {
            SaveStressScreen()
            return true
        }
        "save-verify" -> {
            SaveVerifyScreen()
            return true
        }
    }
    val debugIntent = DebugIntent.from(intent) ?: return false
    // Escala de fuente y señales de accesibilidad forzadas (A7): han de estar fuera de `PocketGBTheme`, que las lee.
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, debugIntent.fontScale),
        LocalAccessibilityOverrides provides debugIntent.accessibilityOverrides(),
    ) {
        PocketGBTheme(appearance = debugIntent.appearance) {
            DebugCatalog(debugIntent)
        }
    }
    return true
}
