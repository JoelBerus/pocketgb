package com.joelbermudez.pocketgb.input

class TouchInputEngine(var geometry: ControlGeometry) {
    private val touches = mutableMapOf<Int, ControlHit>()
    private val points = mutableMapOf<Int, ControlPoint>()

    fun pointerDown(pointerId: Int, point: ControlPoint): Boolean {
        val hit = geometry.hit(point) ?: return false
        if (hit == ControlHit.Single(ControlId.MENU)) return true
        touches[pointerId] = hit
        points[pointerId] = point
        return false
    }

    fun pointerMove(pointerId: Int, point: ControlPoint) {
        val current = touches[pointerId] ?: return
        points[pointerId] = point
        if (current == ControlHit.Single(ControlId.DPAD)) return
        val next = geometry.hit(point)
        if (next != null && next != ControlHit.Single(ControlId.MENU) && next != ControlHit.Single(ControlId.DPAD)) {
            touches[pointerId] = next
        } else {
            touches.remove(pointerId)
            points.remove(pointerId)
        }
    }

    fun pointerUp(pointerId: Int) {
        touches.remove(pointerId)
        points.remove(pointerId)
    }

    fun cancelAll() {
        touches.clear()
        points.clear()
    }

    val mask: Int
        get() = touches.entries.fold(0) { result, (pointerId, hit) ->
            result or when (hit) {
                ControlHit.AB -> GameBoyButton.A.mask or GameBoyButton.B.mask
                is ControlHit.Single -> when (hit.id) {
                    ControlId.DPAD -> points[pointerId]?.let(geometry::dpadMask) ?: 0
                    ControlId.A -> GameBoyButton.A.mask
                    ControlId.B -> GameBoyButton.B.mask
                    ControlId.START -> GameBoyButton.START.mask
                    ControlId.SELECT -> GameBoyButton.SELECT.mask
                    ControlId.MENU -> 0
                }
            }
        }

    val pressed: Set<ControlId>
        get() = buildSet {
            touches.forEach { (pointerId, hit) ->
                when (hit) {
                    ControlHit.AB -> addAll(listOf(ControlId.A, ControlId.B))
                    is ControlHit.Single -> if (
                        hit.id != ControlId.DPAD ||
                        (points[pointerId]?.let(geometry::dpadMask) ?: 0) != 0
                    ) {
                        add(hit.id)
                    }
                }
            }
        }
}
