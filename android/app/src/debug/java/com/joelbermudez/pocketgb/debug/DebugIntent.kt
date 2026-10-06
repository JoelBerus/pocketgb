package com.joelbermudez.pocketgb.debug

import android.content.Intent
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.ThemeMode

/**
 * Argumentos del catálogo (`adb shell am start --es <clave> <valor>`); los mismos nombres que `tools/android-screens.txt`.
 * Todos los extras de texto son opcionales: sin ellos cada pantalla usa los valores de fábrica de la app.
 */
internal data class DebugIntent(
    val screen: String,
    val appearance: AppearanceState,
    val fontScale: Float,
    /** Opacidad de los controles {30, 50, 70, 100}. */
    val opacity: Int = 70,
    val visibility: ControlsVisibility = ControlsVisibility.ALWAYS,
    val dpad: DpadStyle = DpadStyle.CROSS,
    /** `fill` o `integer` (el predeterminado en horizontal). */
    val scaleMode: String = "integer",
    /** Ajuste «Color en juegos de Game Boy». */
    val color: Boolean = false,
    /** Paleta de compatibilidad 0..12. */
    val palette: Int = 0,
    val volume: Float = 1f,
    /** `portrait` o `landscape`: lo aplica el script girando el emulador; aquí solo se anota. */
    val orientation: String = "portrait",
    /** Semilla del color dinámico (`green`, `violet`): la fija el script en el sistema; aquí solo se anota. */
    val dynamicSeed: String? = null,
    val query: String? = null,
    /** `stripes` (rayas), `light` (pantalla blanca) o `damaged` (checksum de cabecera malo). */
    val rom: String = "stripes",
    /** Control seleccionado en el editor (`a`, `b`, `dpad`…) y su escala. */
    val selected: String? = null,
    val scale: Float? = null,
) {
    /** Ajustes de juego equivalentes a los argumentos. */
    fun gameplaySettings(): GameplaySettingsData = GameplaySettingsData(
        opacity = opacity,
        visibility = visibility,
        dpadStyle = dpad,
        integerScaleLandscape = scaleMode != "fill",
        volume = volume,
        colorForGameBoy = color,
        compatPalette = palette,
    ).sanitized()

    companion object {
        fun from(intent: Intent): DebugIntent? {
            val screen = intent.getStringExtra("screen") ?: return null
            fun text(key: String): String? = intent.extras?.get(key)?.toString()?.takeIf { it.isNotBlank() }
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
                opacity = text("opacity")?.toIntOrNull() ?: 70,
                visibility = when (text("visibility")) {
                    "on_touch" -> ControlsVisibility.ON_TOUCH
                    "hidden" -> ControlsVisibility.HIDDEN
                    else -> ControlsVisibility.ALWAYS
                },
                dpad = if (text("dpad") == "arrows") DpadStyle.ARROWS else DpadStyle.CROSS,
                scaleMode = text("scaleMode") ?: "integer",
                color = text("color") == "1" || text("color") == "true",
                palette = text("palette")?.toIntOrNull() ?: 0,
                volume = text("volume")?.toFloatOrNull() ?: 1f,
                orientation = text("orientation") ?: "portrait",
                dynamicSeed = text("dynamicSeed"),
                query = text("query"),
                rom = text("rom") ?: "stripes",
                selected = text("selected"),
                scale = text("scale")?.toFloatOrNull(),
            )
        }
    }
}
