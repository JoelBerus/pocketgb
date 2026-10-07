package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.MAX_CONTROL_SCALE
import com.joelbermudez.pocketgb.settings.MAX_DPAD_SEPARATION
import com.joelbermudez.pocketgb.settings.MAX_SIZE_SCALE
import com.joelbermudez.pocketgb.settings.MIN_CONTROL_SCALE
import com.joelbermudez.pocketgb.settings.MIN_DPAD_SEPARATION
import com.joelbermudez.pocketgb.settings.StoredControlLayout
import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Controles táctiles. [L] y [R] solo existen en Game Boy Advance (N8, = iOS `ControlID.l/.r`); van al final para que los
 * ordinales de los demás (ids virtuales de TalkBack) no cambien.
 */
enum class ControlId {
    DPAD, A, B, START, SELECT, MENU, L, R;

    val isShoulder: Boolean get() = this == L || this == R
}

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
    /** Separación de las flechas separadas (N2): 1,0 = la de fábrica; solo cuenta con [DpadStyle.ARROWS]. */
    val separation: Float = 1f,
) {
    fun scale(id: ControlId): Float = (scales[id] ?: 1f).coerceIn(0.6f, 1.6f)

    fun dpadSeparation(): Float = separation.coerceIn(MIN_DPAD_SEPARATION, MAX_DPAD_SEPARATION)

    companion object {
        /**
         * Disposición guardada por el usuario sobre la de fábrica: lo no guardado conserva su valor por defecto. Con
         * [shoulders] (GBA) la de fábrica es la de Game Boy Advance, con L y R.
         */
        fun from(stored: StoredControlLayout, orientation: ControlsOrientation, shoulders: Boolean = false): ControlLayout {
            val base = defaults(orientation, shoulders)
            val keep: (ControlId) -> Boolean = { shoulders || !it.isShoulder }
            return ControlLayout(
                centers = base.centers + stored.positions.filterKeys(keep),
                scales = base.scales + stored.scales.filterKeys(keep),
                separation = stored.separation,
            )
        }

        /**
         * Disposición de fábrica. Game Boy: imagen 10:9. Game Boy Advance ([shoulders], = iOS `ControlsLayout.defaults(_,
         * shoulders: true)`): imagen 3:2, más ancha y más baja. En vertical, L y R en las esquinas superiores de la zona
         * de controles, sobre la cruceta y sobre A/B; en horizontal, la cruceta (al 0,6), A, B, Start y Select en los
         * márgenes laterales de la imagen y L/R arriba en esos márgenes.
         */
        fun defaults(orientation: ControlsOrientation, shoulders: Boolean = false): ControlLayout =
            if (shoulders) gbaDefaults(orientation) else gbDefaults(orientation)

        private fun gbaDefaults(orientation: ControlsOrientation): ControlLayout = when (orientation) {
            ControlsOrientation.PORTRAIT -> gbDefaults(orientation).let {
                it.copy(centers = it.centers + mapOf(ControlId.L to NormalizedPoint(0.17f, 0.14f), ControlId.R to NormalizedPoint(0.83f, 0.14f)))
            }
            ControlsOrientation.LANDSCAPE -> ControlLayout(
                centers = mapOf(
                    ControlId.DPAD to NormalizedPoint(0.065f, 0.62f),
                    ControlId.A to NormalizedPoint(0.94f, 0.45f),
                    ControlId.B to NormalizedPoint(0.94f, 0.74f),
                    ControlId.START to NormalizedPoint(0.94f, 0.93f),
                    ControlId.SELECT to NormalizedPoint(0.065f, 0.93f),
                    ControlId.MENU to NormalizedPoint(0.5f, 0.06f),
                    ControlId.L to NormalizedPoint(0.065f, 0.1f),
                    ControlId.R to NormalizedPoint(0.94f, 0.1f),
                ),
                scales = mapOf(ControlId.DPAD to 0.6f, ControlId.L to 0.9f, ControlId.R to 0.9f),
            )
        }

        private fun gbDefaults(orientation: ControlsOrientation): ControlLayout = when (orientation) {
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
    /** Con flechas separadas el control mide lo que envuelve el grupo (separación incluida), no el cuadrado de fábrica. */
    val dpadStyle: DpadStyle = DpadStyle.CROSS,
    /** Qué diagonales cuentan en la cruceta (N2). */
    val diagonals: DiagonalMode = DiagonalMode.REDUCED,
    /** N8: dibuja y atiende L y R (solo Game Boy Advance). */
    val shoulders: Boolean = false,
) {
    private val layout = layout

    /** Controles que existen en esta geometría: todos, salvo L y R fuera de GBA (y MENU solo si [showMenu] para tocar). */
    val controls: List<ControlId> = ControlId.entries.filter { shoulders || !it.isShoulder }

    /** Separación efectiva de las flechas (1,0 si el estilo es la cruz). */
    val dpadSeparation: Float = if (dpadStyle == DpadStyle.ARROWS) layout.dpadSeparation() else 1f

    fun scale(id: ControlId): Float =
        (layout.scale(id) * sizeScale).coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE * MAX_SIZE_SCALE)

    val frames: Map<ControlId, ControlBounds> = controls.associateWith { id ->
        val base = baseSize(id)
        val scale = scale(id)
        // El grupo de flechas separadas (separación incluida) es el «control»: su zona táctil y su marco siguen al dibujo.
        val footprint = if (id == ControlId.DPAD && dpadStyle == DpadStyle.ARROWS) DpadShape.footprint(dpadSeparation) else 1f
        val width = base.first * scale * density * footprint
        val height = base.second * scale * density * footprint
        val relative = layout.centers[id] ?: ControlLayout.defaults(orientation, shoulders).centers.getValue(id)
        var halfWidth = min(width / 2f, area.width / 2f)
        var halfHeight = min(height / 2f, area.height / 2f)
        if (base.first == base.second) {
            // Los controles redondos (cruceta, A, B, menú) siguen cuadrados aunque la zona sea más baja que ancha: si no, el
            // dibujo (que sale del ancho) se saldría del área.
            val half = min(halfWidth, halfHeight)
            halfWidth = half
            halfHeight = half
        }
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
        // L y R primero (como iOS): nunca quedan inalcanzables aunque otro control se mueva encima.
        if (shoulders) {
            listOf(ControlId.L, ControlId.R).forEach { id ->
                if (touchFrame(id).expand(6f * density, 6f * density).contains(point)) return ControlHit.Single(id)
            }
        }
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

    /**
     * Dirección bajo [point]; [previous] es la del mismo dedo un instante antes (histéresis, ver [DpadSectors]).
     *
     * Con flechas separadas manda lo que se ve: un toque dentro de un botón da la dirección de ese botón, y el mismo dedo
     * la conserva hasta salir [ARROW_HOLD_MARGIN_DP] de él; la zona muerta del centro se acorta hasta el borde interior de
     * los botones menos [ARROW_DEAD_ZONE_MARGIN_DP]; fuera de los botones (los huecos y el exterior) decide el ángulo.
     */
    fun dpadMask(point: ControlPoint, previous: Int = 0): Int {
        val frame = frames.getValue(ControlId.DPAD)
        val dx = point.x - frame.centerX
        val dy = point.y - frame.centerY
        val radius = frame.width / 2f
        if (dpadStyle != DpadStyle.ARROWS) return dpadMask(dx, dy, radius, diagonals, previous)
        val circles = DpadShape.arrowCircles(frame, dpadSeparation)
        circles.forEach { (button, circle) ->
            if (hypot(point.x - circle.centerX, point.y - circle.centerY) <= circle.radius) return button.mask
        }
        val hold = ARROW_HOLD_MARGIN_DP * density
        circles.forEach { (button, circle) ->
            if (previous == button.mask && hypot(point.x - circle.centerX, point.y - circle.centerY) <= circle.radius + hold) {
                return button.mask
            }
        }
        val up = circles.getValue(GameBoyButton.UP)
        val innerEdge = hypot(up.centerX - frame.centerX, up.centerY - frame.centerY) - up.radius
        val deadZone = min(radius * DpadSectors.DEAD_ZONE, innerEdge - ARROW_DEAD_ZONE_MARGIN_DP * density).coerceAtLeast(0f)
        return DpadSectors.mask(dx, dy, radius, diagonals, previous, deadZone)
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

        /** Flechas separadas: el dedo conserva su botón hasta salir de él este margen. */
        const val ARROW_HOLD_MARGIN_DP = 4f

        /** Flechas separadas: la zona muerta termina este margen antes del borde interior de los botones. */
        const val ARROW_DEAD_ZONE_MARGIN_DP = 2f

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

        /** Dirección de un punto relativo al centro de una cruceta de radio [radius] (ver [DpadSectors]). */
        fun dpadMask(
            dx: Float,
            dy: Float,
            radius: Float,
            mode: DiagonalMode = DiagonalMode.REDUCED,
            previous: Int = 0,
        ): Int = DpadSectors.mask(dx, dy, radius, mode, previous)

        private fun baseSize(id: ControlId): Pair<Float, Float> = when (id) {
            ControlId.DPAD -> 140f to 140f
            ControlId.A, ControlId.B -> 68f to 68f
            ControlId.START, ControlId.SELECT -> 66f to 28f
            ControlId.MENU -> 48f to 48f
            // Cápsulas de L y R (iOS `shoulderSize`, 92×40 pt).
            ControlId.L, ControlId.R -> 92f to 40f
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
