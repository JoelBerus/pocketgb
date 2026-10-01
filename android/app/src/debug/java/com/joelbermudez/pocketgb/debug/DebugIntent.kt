package com.joelbermudez.pocketgb.debug

import android.content.Intent
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.ThemeMode

internal data class DebugIntent(
    val screen: String,
    val appearance: AppearanceState,
    val fontScale: Float,
) {
    companion object {
        fun from(intent: Intent): DebugIntent? {
            val screen = intent.getStringExtra("screen") ?: return null
            val mode = when (intent.getStringExtra("theme")) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            return DebugIntent(
                screen = screen,
                appearance = AppearanceState(
                    themeMode = mode,
                    dynamicColor = intent.getBooleanExtra("dynamicColor", true),
                ),
                fontScale = intent.getFloatExtra("fontScale", 1f).coerceIn(0.85f, 2f),
            )
        }
    }
}
