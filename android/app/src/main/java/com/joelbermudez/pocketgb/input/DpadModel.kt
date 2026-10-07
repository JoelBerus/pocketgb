package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.MAX_DPAD_SEPARATION
import com.joelbermudez.pocketgb.settings.MIN_DPAD_SEPARATION
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Cómo se interpreta un toque sobre la cruceta (N2): qué dirección vale según el ángulo, cuándo el dedo está en la
 * zona muerta del centro y cómo se evita el parpadeo entre sectores vecinos (histéresis).
 *
 * El ángulo se mide en grados, 0 = derecha y creciente en sentido antihorario (con la `y` de pantalla hacia abajo).
 * Cada dirección ocupa un sector centrado en su eje: con [DiagonalMode.REDUCED] la diagonal solo vale a ±15° de los 45°
 * y cada dirección cardinal a ±30° de su eje, así que un toque sobre un brazo casi nunca enciende un segundo brazo.
 */
object DpadSectors {
    /** Radio mínimo (fracción del radio de la cruceta) para que el dedo cuente como una dirección. */
    const val DEAD_ZONE = 0.30f

    /** Histéresis de la zona muerta: una vez activa, la dirección se mantiene hasta bajar de este radio. */
    const val DEAD_ZONE_RELEASE = 0.24f

    /** Histéresis angular: el sector actual se mantiene este margen más allá de su borde (los mismos 8° que en iOS). */
    const val ANGLE_HYSTERESIS_DEG = 8f

    private class Sector(val mask: Int, val centerDeg: Float, val diagonal: Boolean)

    private val sectors = listOf(
        Sector(GameBoyButton.RIGHT.mask, 0f, false),
        Sector(GameBoyButton.RIGHT.mask or GameBoyButton.UP.mask, 45f, true),
        Sector(GameBoyButton.UP.mask, 90f, false),
        Sector(GameBoyButton.UP.mask or GameBoyButton.LEFT.mask, 135f, true),
        Sector(GameBoyButton.LEFT.mask, 180f, false),
        Sector(GameBoyButton.LEFT.mask or GameBoyButton.DOWN.mask, 225f, true),
        Sector(GameBoyButton.DOWN.mask, 270f, false),
        Sector(GameBoyButton.DOWN.mask or GameBoyButton.RIGHT.mask, 315f, true),
    )

    /** Semiancho en grados del sector de una diagonal. */
    fun diagonalHalfWidth(mode: DiagonalMode): Float = when (mode) {
        DiagonalMode.NORMAL -> 22.5f
        DiagonalMode.REDUCED -> 15f
        DiagonalMode.DISABLED -> 0f
    }

    /** Semiancho en grados del sector de una dirección cardinal: lo que las diagonales dejan libre en cada cuadrante. */
    fun cardinalHalfWidth(mode: DiagonalMode): Float = 45f - diagonalHalfWidth(mode)

    private fun halfWidth(sector: Sector, mode: DiagonalMode): Float =
        if (sector.diagonal) diagonalHalfWidth(mode) else cardinalHalfWidth(mode)

    /**
     * Máscara de dirección del punto (`dx`, `dy`) respecto al centro de una cruceta de radio [radius]. [previous] es la
     * máscara que ese mismo dedo tenía en el toque anterior: con ella la zona muerta y los bordes entre sectores tienen
     * histéresis. [deadZoneRadius] es el radio de la zona muerta para activarse (por defecto el 30 % de [radius]; las
     * flechas separadas lo acortan hasta el borde interior de sus botones); una vez activa se suelta a un 80 % de él
     * (0,24 / 0,30). Nunca devuelve direcciones opuestas.
     */
    fun mask(
        dx: Float,
        dy: Float,
        radius: Float,
        mode: DiagonalMode,
        previous: Int = 0,
        deadZoneRadius: Float = radius * DEAD_ZONE,
    ): Int {
        val enter = if (previous != 0) deadZoneRadius * (DEAD_ZONE_RELEASE / DEAD_ZONE) else deadZoneRadius
        if (hypot(dx, dy) < enter) return 0
        var angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        if (previous != 0) {
            val current = sectors.firstOrNull { it.mask == previous && !(it.diagonal && mode == DiagonalMode.DISABLED) }
            if (current != null && angularDistance(angle, current.centerDeg) <= halfWidth(current, mode) + ANGLE_HYSTERESIS_DEG) {
                return current.mask
            }
        }
        // El sector en cuyo interior queda más hondo el ángulo; en el borde exacto gana el primero de la lista.
        return sectors
            .filter { !(it.diagonal && mode == DiagonalMode.DISABLED) }
            .minBy { angularDistance(angle, it.centerDeg) - halfWidth(it, mode) }
            .mask
    }

    private fun angularDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}

/** Un círculo de la cruceta en flechas separadas. */
data class DpadCircle(val centerX: Float, val centerY: Float, val radius: Float)

/**
 * Proporciones de la cruceta, las de la de iOS (`ControlsOverlayView.swift`: cruz y cuatro círculos), expresadas como
 * fracción del ancho nominal W del control.
 */
object DpadShape {
    /** Cruz: la unión de dos rectángulos redondeados de 0,76 W × 0,29 W. */
    const val CROSS_LENGTH = 0.76f
    const val CROSS_THICKNESS = 0.29f

    /** Radio de las esquinas de la cruz, fracción del grosor del brazo. */
    const val CROSS_CORNER = 0.3f

    /** Flechas separadas: cuatro círculos de 0,36 W en rombo. */
    const val ARROW_DIAMETER = 0.36f

    /** Distancia del centro de cada círculo al del grupo con la separación de fábrica: `(1 - 0,36) / 2`, la de iOS. */
    const val ARROW_OFFSET = 0.32f

    /** Distancia con la separación mínima (0,7): los círculos casi se tocan (2 dp de hueco con el control a 140 dp). */
    const val ARROW_OFFSET_MIN = 0.265f

    /**
     * Distancia del centro de cada círculo al del grupo, fracción de W. Por encima de 1,0 crece proporcional a la
     * separación; por debajo se acerca solo hasta [ARROW_OFFSET_MIN], de modo que los círculos nunca se solapan y el
     * deslizador no tiene tramos muertos.
     */
    fun arrowOffset(separation: Float): Float {
        val k = separation.coerceIn(MIN_DPAD_SEPARATION, MAX_DPAD_SEPARATION)
        return if (k >= 1f) ARROW_OFFSET * k
        else ARROW_OFFSET_MIN + (ARROW_OFFSET - ARROW_OFFSET_MIN) * (k - MIN_DPAD_SEPARATION) / (1f - MIN_DPAD_SEPARATION)
    }

    /** Lado del cuadro que envuelve el grupo de flechas, fracción de W (1,0 con la separación de fábrica). */
    fun footprint(separation: Float): Float = 2f * arrowOffset(separation) + ARROW_DIAMETER

    /**
     * Los cuatro brazos de la cruz dentro de [frame] (sin la parte central): su centro cae en el sector de su
     * dirección. Cada brazo llega hasta el borde de la cruz y se detiene en el cuadrado central.
     */
    fun crossArms(frame: ControlBounds): Map<GameBoyButton, ControlBounds> {
        val w = frame.width
        val half = CROSS_LENGTH * w / 2f
        val core = CROSS_THICKNESS * w / 2f
        val cx = frame.centerX
        val cy = frame.centerY
        return mapOf(
            GameBoyButton.UP to ControlBounds(cx - core, cy - half, cx + core, cy - core),
            GameBoyButton.DOWN to ControlBounds(cx - core, cy + core, cx + core, cy + half),
            GameBoyButton.LEFT to ControlBounds(cx - half, cy - core, cx - core, cy + core),
            GameBoyButton.RIGHT to ControlBounds(cx + core, cy - core, cx + half, cy + core),
        )
    }

    /**
     * Los cuatro círculos de las flechas separadas. [frame] es el cuadro que envuelve el grupo ([footprint] × W): el
     * tamaño de los círculos sale de él, así que si la zona de controles lo recorta el grupo se encoge entero.
     */
    fun arrowCircles(frame: ControlBounds, separation: Float): Map<GameBoyButton, DpadCircle> {
        val w = frame.width / footprint(separation)
        val offset = arrowOffset(separation) * w
        val radius = ARROW_DIAMETER * w / 2f
        val cx = frame.centerX
        val cy = frame.centerY
        return mapOf(
            GameBoyButton.UP to DpadCircle(cx, cy - offset, radius),
            GameBoyButton.DOWN to DpadCircle(cx, cy + offset, radius),
            GameBoyButton.LEFT to DpadCircle(cx - offset, cy, radius),
            GameBoyButton.RIGHT to DpadCircle(cx + offset, cy, radius),
        )
    }
}

/**
 * Háptica de la cruceta (N2), la misma regla que en iOS: vibra cuando se activa una dirección que no estaba activa. ↑ →
 * ↑→ vibra (se activa →); ↑→ → ↑ no (no se activa nada); ↑ → nada → ↑ sí (tras soltar, ↑ vuelve a activarse).
 */
object DpadHaptics {
    fun shouldTick(previous: Int, next: Int): Boolean = next != 0 && (next and previous.inv()) != 0
}

/** Anula las direcciones opuestas (↑↓, ←→) de una máscara de botones: si llegan las dos, no se pulsa ninguna. */
fun withoutOpposites(mask: Int): Int {
    var result = mask
    val vertical = GameBoyButton.UP.mask or GameBoyButton.DOWN.mask
    val horizontal = GameBoyButton.LEFT.mask or GameBoyButton.RIGHT.mask
    if (result and vertical == vertical) result = result and vertical.inv()
    if (result and horizontal == horizontal) result = result and horizontal.inv()
    return result
}
