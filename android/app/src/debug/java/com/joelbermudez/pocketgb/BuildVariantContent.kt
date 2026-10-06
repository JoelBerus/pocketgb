package com.joelbermudez.pocketgb

import android.content.Intent
import androidx.compose.runtime.Composable
import com.joelbermudez.pocketgb.debug.DebugCatalog
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.debug.SaveStressScreen
import com.joelbermudez.pocketgb.debug.SaveVerifyScreen
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
    PocketGBTheme(appearance = debugIntent.appearance) {
        DebugCatalog(debugIntent)
    }
    return true
}
