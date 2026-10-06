package com.joelbermudez.pocketgb.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Quien recibe el mando mientras hay una partida sin hojas ni diálogos encima. Siempre en el hilo principal. */
interface GamepadSink {
    /** `true` si consume la tecla. */
    fun onKey(keyCode: Int, down: Boolean): Boolean

    /** `true` si consume el movimiento de ejes. */
    fun onAxes(hatX: Float, hatY: Float, x: Float, y: Float): Boolean

    /** La ventana perdió el foco: soltar todo. */
    fun onFocusLost()
}

/**
 * Puente entre la actividad (`dispatchKeyEvent` / `dispatchGenericMotionEvent`) y el [GamepadSink] vigente. Sin sink
 * registrado nada se consume y la navegación por foco de Compose sigue funcionando.
 */
object GamepadRouter {
    private var sink: GamepadSink? = null

    /** Registra [new] y devuelve cómo retirarlo (solo retira si sigue siendo el vigente). */
    fun register(new: GamepadSink): () -> Unit {
        sink = new
        return { if (sink === new) sink = null }
    }

    fun dispatchKey(event: KeyEvent): Boolean {
        val current = sink ?: return false
        if (!isPadSource(event.source) || event.keyCode == KeyEvent.KEYCODE_BACK) return false
        return current.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
    }

    fun dispatchMotion(event: MotionEvent): Boolean {
        val current = sink ?: return false
        if (!isPadMotion(event.source, event.device?.sources ?: 0) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        return current.onAxes(
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
        )
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) sink?.onFocusLost()
    }

    /**
     * Solo `SOURCE_GAMEPAD` o `SOURCE_JOYSTICK`. `SOURCE_DPAD` solo (teclados con flechas, mandos a distancia) no cuenta:
     * un mando real con cruceta ya trae también `SOURCE_GAMEPAD` (A7-H1).
     */
    private fun isPadSource(source: Int) = isGamepadSources(source)
}
