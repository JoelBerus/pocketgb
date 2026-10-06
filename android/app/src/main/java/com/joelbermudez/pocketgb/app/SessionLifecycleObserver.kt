package com.joelbermudez.pocketgb.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.joelbermudez.pocketgb.game.GameSession

/**
 * `ON_PAUSE` y `ON_STOP` pausan la partida y vacían la SRAM con un plazo acotado de 3 s (J5, SPEC §6).
 * Si el plazo se agota o falla, el juego queda en pausa con el guardado pendiente: `ON_STOP` (o la vuelta
 * a primer plano) lo reintenta, y intentar salir lo cuenta como fallo. Volver a primer plano NO reanuda:
 * el juego queda en pausa. [GameSession.pause] es idempotente, así que repetir eventos es inocuo.
 */
class SessionLifecycleObserver(
    private val game: GameSession,
) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_RESUME) {
            game.retryPendingSave()
        }
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
            try {
                game.pause()
            } catch (_: Exception) {
                // La sesión pudo cerrarse justo antes (salida en curso): nada que pausar.
            }
        }
    }
}
