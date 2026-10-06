package com.joelbermudez.pocketgb.input

/**
 * Rectángulos donde se difieren los gestos del sistema (K5). Android solo respeta 200 dp verticales por borde: se
 * reparten por lado dando prioridad a la cruceta y recortando (centrado en el control) lo que no quepa.
 */
object GestureExclusion {
    const val EDGE_BUDGET_DP = 200f

    fun rects(geometry: ControlGeometry, viewWidth: Float, density: Float): List<ControlBounds> {
        val budget = EDGE_BUDGET_DP * density
        val result = mutableListOf<ControlBounds>()
        val spent = mutableMapOf(false to 0f, true to 0f) // lado derecho?
        listOf(ControlId.DPAD, ControlId.A, ControlId.B).forEach { id ->
            val frame = geometry.touchFrame(id)
            val right = frame.centerX >= viewWidth / 2f
            val remaining = budget - spent.getValue(right)
            if (remaining <= 0f) return@forEach
            val height = minOf(frame.height, remaining)
            // Nunca sobre el recorte: la exclusión se recorta al área segura de los controles.
            val area = geometry.area
            result += ControlBounds(
                maxOf(frame.left, area.left), frame.centerY - height / 2f,
                minOf(frame.right, area.right), frame.centerY + height / 2f,
            )
            spent[right] = spent.getValue(right) + height
        }
        return result
    }
}
