package com.joelbermudez.pocketgb.debug

import android.content.Intent
import com.joelbermudez.pocketgb.input.GameBoyButton
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.StoredControlLayout
import com.joelbermudez.pocketgb.settings.ThemeMode
import com.joelbermudez.pocketgb.ui.a11y.AccessibilityOverrides
import com.joelbermudez.pocketgb.ui.a11y.ContrastLevel

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
    /** A7 L1: simula un mando conectado (oculta los controles táctiles salvo `showTouch`). */
    val controller: Boolean = false,
    /** A7 L1: ajuste «Mostrar controles táctiles con mando». */
    val showTouch: Boolean = false,
    /** A7 L2: contraste del sistema forzado (`medium` o `high`); `null` lo lee del sistema. */
    val contrast: ContrastLevel? = null,
    /** A7 L2: reducir movimiento forzado; `null` lo lee del sistema. */
    val reduceMotion: Boolean? = null,
    /** N2: diagonales de la cruceta (`normal`, `reduced`, `disabled`); por defecto las de fábrica (reducidas). */
    val diagonals: DiagonalMode = DiagonalMode.REDUCED,
    /** N2: separación de las flechas separadas 0,7..1,5 (en las dos orientaciones); `null` = 1,0. */
    val separation: Float? = null,
    /** N2: direcciones de la cruceta dibujadas como pulsadas (máscara de [GameBoyButton]); solo se dibujan, no mandan nada. */
    val pressedDpad: Int = 0,
) {
    /** Señales de accesibilidad forzadas por los argumentos (fuente grande sale de [fontScale] en `LocalDensity`). */
    fun accessibilityOverrides(): AccessibilityOverrides = AccessibilityOverrides(
        reduceMotion = reduceMotion,
        contrast = contrast,
    )

    /** Ajustes de juego equivalentes a los argumentos. */
    fun gameplaySettings(): GameplaySettingsData = GameplaySettingsData(
        opacity = opacity,
        visibility = visibility,
        dpadStyle = dpad,
        integerScaleLandscape = scaleMode != "fill",
        volume = volume,
        colorForGameBoy = color,
        compatPalette = palette,
        showTouchControlsWithController = showTouch,
        diagonalMode = diagonals,
        portraitLayout = StoredControlLayout(separation = separation ?: 1f),
        landscapeLayout = StoredControlLayout(separation = separation ?: 1f),
    ).sanitized()

    companion object {
        private fun flag(value: String?): Boolean = value == "1" || value == "true"

        /** `up`, `down`, `left`, `right` o una diagonal (`upright`, `upleft`, `downright`, `downleft`). */
        internal fun pressedMask(value: String?): Int {
            val name = value?.lowercase() ?: return 0
            var mask = 0
            if ("up" in name) mask = mask or GameBoyButton.UP.mask
            if ("down" in name) mask = mask or GameBoyButton.DOWN.mask
            if ("left" in name) mask = mask or GameBoyButton.LEFT.mask
            if ("right" in name) mask = mask or GameBoyButton.RIGHT.mask
            return mask
        }

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
                // El script lo manda como `--ef` (Float) y los tests instrumentados como texto: se aceptan ambos.
                fontScale = (text("fontScale")?.toFloatOrNull() ?: 1f).coerceIn(0.85f, 2f),
                opacity = text("opacity")?.toIntOrNull() ?: 70,
                visibility = when (text("visibility")) {
                    "on_touch" -> ControlsVisibility.ON_TOUCH
                    "hidden" -> ControlsVisibility.HIDDEN
                    else -> ControlsVisibility.ALWAYS
                },
                dpad = if (text("dpad") == "arrows") DpadStyle.ARROWS else DpadStyle.CROSS,
                scaleMode = text("scaleMode") ?: "integer",
                color = flag(text("color")),
                palette = text("palette")?.toIntOrNull() ?: 0,
                volume = text("volume")?.toFloatOrNull() ?: 1f,
                orientation = text("orientation") ?: "portrait",
                dynamicSeed = text("dynamicSeed"),
                query = text("query"),
                rom = text("rom") ?: "stripes",
                selected = text("selected"),
                scale = text("scale")?.toFloatOrNull(),
                controller = flag(text("controller")),
                showTouch = flag(text("showTouch")),
                contrast = when (text("contrast")) {
                    "high" -> ContrastLevel.HIGH
                    "medium" -> ContrastLevel.MEDIUM
                    else -> null
                },
                reduceMotion = text("reduceMotion")?.let { flag(it) },
                diagonals = when (text("diagonals")) {
                    "normal" -> DiagonalMode.NORMAL
                    "disabled" -> DiagonalMode.DISABLED
                    else -> DiagonalMode.REDUCED
                },
                separation = text("separation")?.toFloatOrNull(),
                pressedDpad = pressedMask(text("pressed")),
            )
        }
    }
}
