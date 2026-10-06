package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.GameplaySettingsData

/**
 * Cómo se dibujan los controles (K12). La opacidad {30, 50, 70, 100} % es solo visual: el área táctil no cambia.
 * [showMenu] es `false` desde A6 (K13): el control MENU sigue en la geometría pero ni se dibuja ni se toca.
 */
data class ControlsRenderOptions(
    val opacity: Int = 70,
    val dpadStyle: DpadStyle = DpadStyle.CROSS,
    val showMenu: Boolean = false,
    /** Contraste alto del sistema (R10): relleno sólido ≥ 90 %, anillo de 2 dp opaco y etiquetas opacas, sin importar la opacidad elegida. */
    val highContrast: Boolean = false,
) {
    val opacityFraction: Float get() = opacity.coerceIn(0, 100) / 100f

    /** Las etiquetas nunca bajan del 70 % de opacidad: se leen aunque el relleno sea casi transparente. */
    val labelFraction: Float get() = maxOf(opacity, MIN_LABEL_OPACITY).coerceAtMost(100) / 100f

    /** Alfa del relleno de un control, ya multiplicado por el desvanecido [fade]. */
    fun fillAlpha(pressed: Boolean, fade: Float): Float {
        val base = if (pressed) FILL_PRESSED_ALPHA else FILL_ALPHA
        return if (highContrast) maxOf(base, SOLID_ALPHA) * fade else base * opacityFraction * fade
    }

    /** Alfa del anillo: los acentos (A, B) casi opacos; el neutro, a media opacidad salvo en contraste alto. */
    fun ringAlpha(accent: Boolean, fade: Float): Float =
        if (highContrast) fade else (if (accent) 0.95f else 0.5f) * opacityFraction * fade

    /** Alfa de etiquetas y flechas. */
    fun labelAlpha(fade: Float): Float = if (highContrast) fade else labelFraction * fade

    /** Grosor del anillo (el mismo con contraste alto: cambia su opacidad, que pasa a 100 %). */
    val ringWidthDp: Float get() = RING_WIDTH_DP

    /** Alfa de la capa oscura detrás de cada control. */
    fun scrimAlpha(fade: Float): Float = if (highContrast) 0.5f * fade else 0.28f * opacityFraction * fade

    companion object {
        const val MIN_LABEL_OPACITY = 70
        const val FILL_ALPHA = 0.72f
        const val FILL_PRESSED_ALPHA = 0.92f
        const val SOLID_ALPHA = 0.95f
        const val RING_WIDTH_DP = 2f

        fun from(settings: GameplaySettingsData) = ControlsRenderOptions(settings.opacity, settings.dpadStyle)
    }
}

/** Márgenes del área segura (cutout y barras) en píxeles: la geometría de los controles vive dentro de ellos. */
data class SafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    companion object {
        val NONE = SafeInsets()
    }
}
