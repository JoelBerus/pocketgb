package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.ControlsVisibility

/**
 * Cuándo se ven los controles (K12). Siempre: opacos. Al tocar: se desvanecen a los 3 s sin toques y cualquier
 * toque los vuelve a mostrar. Ocultos: nunca se dibujan, pero siguen respondiendo; una pista breve avisa de ello al
 * empezar o al elegir ese ajuste. El reloj se inyecta para probarlo.
 */
class ControlsFadeController(
    visibility: ControlsVisibility = ControlsVisibility.ALWAYS,
    private val clock: () -> Long,
) {
    private var since = clock()

    /**
     * Reducir movimiento (R11): sin rampa. Pasado el plazo los controles desaparecen de golpe (alfa 1 → 0) en lugar de
     * desvanecerse en [FADE_DURATION_MS].
     */
    var reduceMotion: Boolean = false

    var visibility: ControlsVisibility = visibility
        set(value) {
            if (field != value) {
                field = value
                since = clock()
            }
        }

    /** Un toque en pantalla: en «Al tocar» reinicia el plazo; en «Ocultos» no hace nada. */
    fun onTouch() {
        if (visibility == ControlsVisibility.ON_TOUCH) since = clock()
    }

    /** Reinicia el plazo (p. ej. al entrar en la partida o al salir del editor). */
    fun restart() {
        since = clock()
    }

    fun alpha(): Float = when (visibility) {
        ControlsVisibility.ALWAYS -> 1f
        ControlsVisibility.HIDDEN -> 0f
        ControlsVisibility.ON_TOUCH -> ramp()
    }

    /** Opacidad de la pista «Controles ocultos…»: solo con [ControlsVisibility.HIDDEN] y durante los primeros 3 s. */
    fun hintAlpha(): Float = if (visibility == ControlsVisibility.HIDDEN) ramp() else 0f

    /** Milisegundos hasta que [alpha] o [hintAlpha] cambien (el plazo o el siguiente fotograma del desvanecido); `null` si no cambiarán. */
    fun msUntilChange(): Long? {
        if (visibility == ControlsVisibility.ALWAYS) return null
        val elapsed = clock() - since
        return when {
            elapsed < DELAY_MS -> DELAY_MS - elapsed
            !reduceMotion && elapsed < DELAY_MS + FADE_DURATION_MS -> FRAME_MS
            else -> null
        }
    }

    private fun ramp(): Float {
        val elapsed = clock() - since
        return when {
            elapsed <= DELAY_MS -> 1f
            reduceMotion -> 0f
            elapsed >= DELAY_MS + FADE_DURATION_MS -> 0f
            else -> 1f - (elapsed - DELAY_MS).toFloat() / FADE_DURATION_MS
        }
    }

    companion object {
        const val DELAY_MS = 3_000L
        const val FADE_DURATION_MS = 400L
        private const val FRAME_MS = 16L
    }
}
