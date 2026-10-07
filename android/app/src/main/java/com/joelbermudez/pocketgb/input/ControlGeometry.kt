package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.MAX_CONTROL_SCALE
import com.joelbermudez.pocketgb.settings.MAX_SIZE_SCALE
import com.joelbermudez.pocketgb.settings.MIN_CONTROL_SCALE
import com.joelbermudez.pocketgb.settings.StoredControlLayout
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ControlId { DPAD, A, B, START, SELECT, MENU }

enum class ControlsOrientation { PORTRAIT, LANDSCAPE }

@Serializable
data class NormalizedPoint(val x: Float, val y: Float)
data class ControlPoint(val x: Float, val y: Float)

data class ControlBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(point: ControlPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun expand(horizontal: Float, vertical: Float): ControlBounds = ControlBounds(
        left - horizontal,
        top - vertical,
        right + horizontal,
        bottom + vertical,
    )
}

data class ControlLayout(
    val centers: Map<ControlId, NormalizedPoint>,
    val scales: Map<ControlId, Float> = emptyMap(),
) {
    fun scale(id: ControlId): Float = (scales[id] ?: 1f).coerceIn(0.6f, 1.6f)

    companion object {
        /** Disposición guardada por el usuario sobre la de fábrica: lo no guardado conserva su valor por defecto. */
        fun from(stored: StoredControlLayout, orientation: ControlsOrientation): ControlLayout {
            val base = defaults(orientation)
            return ControlLayout(centers = base.centers + stored.positions, scales = base.scales + stored.scales)
        }

        fun defaults(orientation: ControlsOrientation): ControlLayout = when (orientation) {
            ControlsOrientation.PORTRAIT -> ControlLayout(
                centers = mapOf(
                    ControlId.DPAD to NormalizedPoint(0.25f, 0.44f),
                    ControlId.A to NormalizedPoint(0.84f, 0.36f),
                    ControlId.B to NormalizedPoint(0.64f, 0.52f),
                    ControlId.START to NormalizedPoint(0.59f, 0.86f),
                    ControlId.SELECT to NormalizedPoint(0.41f, 0.86f),
                    ControlId.MENU to NormalizedPoint(0.5f, 0.07f),
                ),
            )
            ControlsOrientation.LANDSCAPE -> ControlLayout(
                centers = mapOf(
                    ControlId.DPAD to NormalizedPoint(0.12f, 0.62f),
                    ControlId.A to NormalizedPoint(0.91f, 0.52f),
                    ControlId.B to NormalizedPoint(0.81f, 0.72f),
                    ControlId.START to NormalizedPoint(0.56f, 0.93f),
                    ControlId.SELECT to NormalizedPoint(0.44f, 0.93f),
                    ControlId.MENU to NormalizedPoint(0.5f, 0.06f),
                ),
            )
        }
    }
}

sealed interface ControlHit {
    data class Single(val id: ControlId) : ControlHit
    data object AB : ControlHit
}

class ControlGeometry(
    layout: ControlLayout,
    orientation: ControlsOrientation,
    val area: ControlBounds,
    private val density: Float = 1f,
    /** Escala global (Ajustes › Controles): multiplica la de cada control; el producto se recorta a 0,6..1,6×1,15. */
    private val sizeScale: Float = 1f,
    /** El control MENU solo se puede tocar si se dibuja (K13: en A6 va oculto). */
    private val showMenu: Boolean = true,
) {
    private val layout = layout

    fun scale(id: ControlId): Float =
        (layout.scale(id) * sizeScale).coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE * MAX_SIZE_SCALE)

    val frames: Map<ControlId, ControlBounds> = ControlId.entries.associateWith { id ->
        val base = baseSize(id)
        val scale = scale(id)
        val width = base.first * scale * density
        val height = base.second * scale * density
        val relative = layout.centers[id] ?: ControlLayout.defaults(orientation).centers.getValue(id)
        val halfWidth = min(width / 2f, area.width / 2f)
        val halfHeight = min(height / 2f, area.height / 2f)
        val centerX = (area.left + area.width * relative.x)
            .coerceIn(area.left + halfWidth, area.right - halfWidth)
        val centerY = (area.top + area.height * relative.y)
            .coerceIn(area.top + halfHeight, area.bottom - halfHeight)
        ControlBounds(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
    }

    val abFrame: ControlBounds = run {
        val a = frames.getValue(ControlId.A)
        val b = frames.getValue(ControlId.B)
        val diameter = 36f * density
        val centerX = (a.centerX + b.centerX) / 2f
        val centerY = (a.centerY + b.centerY) / 2f
        ControlBounds(
            centerX - diameter / 2f,
            centerY - diameter / 2f,
            centerX + diameter / 2f,
            centerY + diameter / 2f,
        )
    }

    fun touchFrame(id: ControlId): ControlBounds {
        val frame = frames.getValue(id)
        return frame.expand(
            horizontal = max(0f, (MIN_TOUCH_SIZE * density - frame.width) / 2f),
            vertical = max(0f, (MIN_TOUCH_SIZE * density - frame.height) / 2f),
        )
    }

    fun hit(point: ControlPoint): ControlHit? {
        if (inCircle(point, abFrame)) return ControlHit.AB
        listOf(ControlId.A, ControlId.B, ControlId.DPAD, ControlId.MENU).forEach { id ->
            if (id == ControlId.MENU && !showMenu) return@forEach
            if (inCircle(point, touchFrame(id))) return ControlHit.Single(id)
        }
        listOf(ControlId.START, ControlId.SELECT).forEach { id ->
            if (touchFrame(id).expand(6f * density, 6f * density).contains(point)) {
                return ControlHit.Single(id)
            }
        }
        return null
    }

    fun dpadMask(point: ControlPoint): Int {
        val frame = frames.getValue(ControlId.DPAD)
        return dpadMask(point.x - frame.centerX, point.y - frame.centerY, frame.width / 2f)
    }

    /**
     * Centro normalizado donde queda [id] si se arrastra hasta [point] en el editor: ajustado a 8 dp del borde del área
     * si está a menos de [SNAP_THRESHOLD_DP] de él, y nunca fuera de ella.
     */
    fun snappedCenter(id: ControlId, point: ControlPoint): NormalizedPoint {
        val frame = frames.getValue(id)
        val x = snapAxis(point.x, frame.width / 2f, area.left, area.right, EDGE_MARGIN_DP * density, SNAP_THRESHOLD_DP * density)
        val y = snapAxis(point.y, frame.height / 2f, area.top, area.bottom, EDGE_MARGIN_DP * density, SNAP_THRESHOLD_DP * density)
        return NormalizedPoint(
            ((x - area.left) / area.width).coerceIn(0f, 1f),
            ((y - area.top) / area.height).coerceIn(0f, 1f),
        )
    }

    private fun inCircle(point: ControlPoint, bounds: ControlBounds): Boolean =
        hypot(point.x - bounds.centerX, point.y - bounds.centerY) <= bounds.width / 2f

    companion object {
        /**
         * Área segura de los controles (A7 R15): la vista menos los márgenes ([SafeInsets]: recorte de pantalla, barras y
         * gestos). La imagen del juego puede invadir el recorte; los controles, nunca. Nunca colapsa a menos de 1 px.
         */
        fun safeArea(width: Float, height: Float, insets: SafeInsets): ControlBounds = ControlBounds(
            insets.left.toFloat(),
            insets.top.toFloat(),
            (width - insets.right).coerceAtLeast(insets.left + 1f),
            (height - insets.bottom).coerceAtLeast(insets.top + 1f),
        )

        const val MIN_TOUCH_SIZE = 48f
        const val EDGE_MARGIN_DP = 8f
        const val SNAP_THRESHOLD_DP = 12f

        /** Centro en un eje: dentro de [min]+[margin]+[half]..[max]-[margin]-[half], pegado al borde si está a menos de [threshold]. */
        fun snapAxis(center: Float, half: Float, min: Float, max: Float, margin: Float, threshold: Float): Float {
            val low = min + margin + half
            val high = max - margin - half
            if (low >= high) return (min + max) / 2f
            return when {
                center <= low + threshold -> low
                center >= high - threshold -> high
                else -> center
            }
        }

        /**
         * Las cuatro zonas de dirección dentro de la cruceta (para dibujar los brazos de la cruz o las cuatro flechas
         * separadas): su centro cae en el sector de su dirección de [dpadMask].
         */
        fun dpadArms(frame: ControlBounds): Map<GameBoyButton, ControlBounds> {
            val third = frame.width / 3f
            val thirdH = frame.height / 3f
            return mapOf(
                GameBoyButton.UP to ControlBounds(frame.left + third, frame.top, frame.right - third, frame.top + thirdH),
                GameBoyButton.DOWN to ControlBounds(frame.left + third, frame.bottom - thirdH, frame.right - third, frame.bottom),
                GameBoyButton.LEFT to ControlBounds(frame.left, frame.top + thirdH, frame.left + third, frame.bottom - thirdH),
                GameBoyButton.RIGHT to ControlBounds(frame.right - third, frame.top + thirdH, frame.right, frame.bottom - thirdH),
            )
        }

        fun dpadMask(dx: Float, dy: Float, radius: Float): Int {
            if (hypot(dx, dy) < radius * 0.25f) return 0
            val angle = atan2(-dy, dx)
            val fullTurn = (2.0 * PI).toFloat()
            val shifted = (angle + fullTurn + (PI / 8.0).toFloat()) % fullTurn
            val sector = (shifted / (PI / 4.0).toFloat()).toInt() % 8
            val right = GameBoyButton.RIGHT.mask
            val up = GameBoyButton.UP.mask
            val left = GameBoyButton.LEFT.mask
            val down = GameBoyButton.DOWN.mask
            return intArrayOf(
                right,
                right or up,
                up,
                up or left,
                left,
                left or down,
                down,
                down or right,
            )[sector]
        }

        private fun baseSize(id: ControlId): Pair<Float, Float> = when (id) {
            ControlId.DPAD -> 140f to 140f
            ControlId.A, ControlId.B -> 68f to 68f
            ControlId.START, ControlId.SELECT -> 66f to 28f
            ControlId.MENU -> 48f to 48f
        }
    }
}

/** Unión de márgenes seguros (p. ej. recorte de pantalla y gestos del sistema): el mayor de cada lado. */
fun SafeInsets.union(other: SafeInsets): SafeInsets = SafeInsets(
    left = maxOf(left, other.left),
    top = maxOf(top, other.top),
    right = maxOf(right, other.right),
    bottom = maxOf(bottom, other.bottom),
)
