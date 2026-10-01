package com.joelbermudez.pocketgb.input

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ControlId { DPAD, A, B, START, SELECT, MENU }

enum class ControlsOrientation { PORTRAIT, LANDSCAPE }

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
    private val area: ControlBounds,
) {
    val frames: Map<ControlId, ControlBounds> = ControlId.entries.associateWith { id ->
        val base = baseSize(id)
        val scale = layout.scale(id)
        val width = base.first * scale
        val height = base.second * scale
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
        val diameter = 36f
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
            horizontal = max(0f, (MIN_TOUCH_SIZE - frame.width) / 2f),
            vertical = max(0f, (MIN_TOUCH_SIZE - frame.height) / 2f),
        )
    }

    fun hit(point: ControlPoint): ControlHit? {
        if (inCircle(point, abFrame)) return ControlHit.AB
        listOf(ControlId.A, ControlId.B, ControlId.DPAD, ControlId.MENU).forEach { id ->
            if (inCircle(point, touchFrame(id))) return ControlHit.Single(id)
        }
        listOf(ControlId.START, ControlId.SELECT).forEach { id ->
            if (touchFrame(id).expand(6f, 6f).contains(point)) return ControlHit.Single(id)
        }
        return null
    }

    fun dpadMask(point: ControlPoint): Int {
        val frame = frames.getValue(ControlId.DPAD)
        return dpadMask(point.x - frame.centerX, point.y - frame.centerY, frame.width / 2f)
    }

    private fun inCircle(point: ControlPoint, bounds: ControlBounds): Boolean =
        hypot(point.x - bounds.centerX, point.y - bounds.centerY) <= bounds.width / 2f

    companion object {
        const val MIN_TOUCH_SIZE = 48f

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
