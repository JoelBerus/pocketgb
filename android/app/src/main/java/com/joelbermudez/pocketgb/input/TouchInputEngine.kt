package com.joelbermudez.pocketgb.input

class TouchInputEngine(var geometry: ControlGeometry) {
    private val touches = mutableMapOf<Int, ControlHit>()
    private val points = mutableMapOf<Int, ControlPoint>()

    /** Dirección vigente de cada dedo que captura la cruceta: es la «previa» de la histéresis del siguiente toque (N2). */
    private val dpadMasks = mutableMapOf<Int, Int>()

    fun pointerDown(pointerId: Int, point: ControlPoint): Boolean {
        val hit = geometry.hit(point) ?: return false
        if (hit == ControlHit.Single(ControlId.MENU)) return true
        touches[pointerId] = hit
        points[pointerId] = point
        if (hit == ControlHit.Single(ControlId.DPAD)) dpadMasks[pointerId] = geometry.dpadMask(point)
        return false
    }

    fun pointerMove(pointerId: Int, point: ControlPoint) {
        val current = touches[pointerId] ?: return
        points[pointerId] = point
        if (current == ControlHit.Single(ControlId.DPAD)) {
            // El dedo queda capturado por la cruceta hasta que se levanta, aunque salga de ella.
            dpadMasks[pointerId] = geometry.dpadMask(point, previous = dpadMasks[pointerId] ?: 0)
            return
        }
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
        dpadMasks.remove(pointerId)
    }

    fun cancelAll() {
        touches.clear()
        points.clear()
        dpadMasks.clear()
    }

    val mask: Int
        get() = touches.entries.fold(0) { result, (pointerId, hit) ->
            result or when (hit) {
                ControlHit.AB -> GameBoyButton.A.mask or GameBoyButton.B.mask
                is ControlHit.Single -> when (hit.id) {
                    ControlId.DPAD -> dpadMasks[pointerId] ?: 0
                    ControlId.A -> GameBoyButton.A.mask
                    ControlId.B -> GameBoyButton.B.mask
                    ControlId.START -> GameBoyButton.START.mask
                    ControlId.SELECT -> GameBoyButton.SELECT.mask
                    ControlId.MENU -> 0
                }
            }
        }

    /** Solo la parte de cruceta de [mask]: la usa la háptica al activarse una dirección nueva. */
    val dpadMask: Int
        get() = touches.entries.fold(0) { result, (pointerId, hit) ->
            if (hit == ControlHit.Single(ControlId.DPAD)) result or (dpadMasks[pointerId] ?: 0) else result
        }

    val pressed: Set<ControlId>
        get() = buildSet {
            touches.forEach { (pointerId, hit) ->
                when (hit) {
                    ControlHit.AB -> addAll(listOf(ControlId.A, ControlId.B))
                    is ControlHit.Single -> if (
                        hit.id != ControlId.DPAD ||
                        (dpadMasks[pointerId] ?: 0) != 0
                    ) {
                        add(hit.id)
                    }
                }
            }
        }
}
