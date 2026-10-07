package com.joelbermudez.pocketgb.input

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.theme.PocketDarkColorScheme

/**
 * Colores de los controles (N2), tomados de los roles tonales del esquema Material del juego (siempre oscuro): sin
 * vidrio. [pressed] sobre [surface] da al menos 3:1 en los tres esquemas (estándar, medio y alto contraste).
 */
data class ControlsPalette(
    /** Fondo del disco de la cruz. */
    val base: Int,
    /** Brazos, flechas y botones sin pulsar. */
    val surface: Int,
    /** Símbolos y etiquetas sobre [surface]. */
    val onSurface: Int,
    /** Brazo, flecha o botón pulsado. */
    val pressed: Int,
    /** Símbolos y etiquetas sobre [pressed]. */
    val onPressed: Int,
    /** Contorno de los controles neutros. */
    val outline: Int,
) {
    companion object {
        fun from(scheme: ColorScheme) = ControlsPalette(
            base = scheme.surface.toArgb(),
            surface = scheme.surfaceVariant.toArgb(),
            onSurface = scheme.onSurfaceVariant.toArgb(),
            pressed = scheme.primary.toArgb(),
            onPressed = scheme.onPrimary.toArgb(),
            outline = scheme.outline.toArgb(),
        )

        val Default: ControlsPalette = from(PocketDarkColorScheme)
    }
}

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
    /** Qué diagonales cuentan en la cruceta (N2). */
    val diagonals: DiagonalMode = DiagonalMode.REDUCED,
    val palette: ControlsPalette = ControlsPalette.Default,
) {
    val opacityFraction: Float get() = opacity.coerceIn(0, 100) / 100f

    /** Las etiquetas nunca bajan del 70 % de opacidad: se leen aunque el relleno sea casi transparente. */
    val labelFraction: Float get() = maxOf(opacity, MIN_LABEL_OPACITY).coerceAtMost(100) / 100f

    /**
     * Alfa del relleno de un control, ya multiplicado por el desvanecido [fade]. El neutro sigue la opacidad elegida; el
     * pulsado no: pulsar siempre se ve (N2), aunque los controles sean casi transparentes.
     */
    fun fillAlpha(pressed: Boolean, fade: Float): Float = when {
        highContrast -> maxOf(if (pressed) FILL_PRESSED_ALPHA else FILL_ALPHA, SOLID_ALPHA) * fade
        pressed -> FILL_PRESSED_ALPHA * fade
        else -> FILL_ALPHA * opacityFraction * fade
    }

    /** Alfa del anillo: los acentos (A, B) casi opacos; el neutro, a media opacidad salvo en contraste alto. */
    fun ringAlpha(accent: Boolean, fade: Float): Float =
        if (highContrast) fade else (if (accent) 0.95f else 0.5f) * opacityFraction * fade

    /** Alfa de etiquetas y flechas. */
    fun labelAlpha(fade: Float): Float = if (highContrast) fade else labelFraction * fade

    /** Grosor del anillo (el mismo con contraste alto: cambia su opacidad, que pasa a 100 %). */
    val ringWidthDp: Float get() = RING_WIDTH_DP

    /**
     * Alfa de la capa oscura detrás de cada control: crece con la opacidad (la de iOS: `0,3 + 0,3 × opacidad`) y nunca
     * desaparece, así el pulsado se distingue también sobre un fotograma blanco.
     */
    fun scrimAlpha(fade: Float): Float = if (highContrast) 0.5f * fade else (SCRIM_BASE + SCRIM_PER_OPACITY * opacityFraction) * fade

    companion object {
        const val MIN_LABEL_OPACITY = 70
        const val FILL_ALPHA = 0.72f
        const val FILL_PRESSED_ALPHA = 0.92f
        const val SOLID_ALPHA = 0.95f
        const val RING_WIDTH_DP = 2f
        const val SCRIM_BASE = 0.3f
        const val SCRIM_PER_OPACITY = 0.3f

        fun from(settings: GameplaySettingsData) =
            ControlsRenderOptions(settings.opacity, settings.dpadStyle, diagonals = settings.diagonalMode)
    }
}

/** Márgenes del área segura (cutout y barras) en píxeles: la geometría de los controles vive dentro de ellos. */
data class SafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    companion object {
        val NONE = SafeInsets()
    }
}
