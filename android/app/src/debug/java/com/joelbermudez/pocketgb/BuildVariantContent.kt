package com.joelbermudez.pocketgb

import android.content.Intent
import androidx.compose.runtime.Composable
import com.joelbermudez.pocketgb.debug.DebugCatalog
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme

@Composable
internal fun buildVariantContent(intent: Intent): Boolean {
    val debugIntent = DebugIntent.from(intent) ?: return false
    PocketGBTheme(appearance = debugIntent.appearance) {
        DebugCatalog(debugIntent)
    }
    return true
}
