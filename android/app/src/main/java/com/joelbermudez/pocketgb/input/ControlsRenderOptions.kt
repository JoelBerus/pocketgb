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
) {
    val opacityFraction: Float get() = opacity.coerceIn(0, 100) / 100f

    /** Las etiquetas nunca bajan del 70 % de opacidad: se leen aunque el relleno sea casi transparente. */
    val labelFraction: Float get() = maxOf(opacity, MIN_LABEL_OPACITY).coerceAtMost(100) / 100f

    companion object {
        const val MIN_LABEL_OPACITY = 70

        fun from(settings: GameplaySettingsData) = ControlsRenderOptions(settings.opacity, settings.dpadStyle)
    }
}

/** Márgenes del área segura (cutout y barras) en píxeles: la geometría de los controles vive dentro de ellos. */
data class SafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    companion object {
        val NONE = SafeInsets()
    }
}
