package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.saves.BlockedFingerprints
import com.joelbermudez.pocketgb.saves.CloseResult
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Dueño, a NIVEL DE APLICACIÓN, de las sesiones cuyo ViewModel se destruyó (A5 2ª vuelta, A5V2-H1). Sobrevive a
 * cualquier ViewModel y cumple tres cosas:
 *
 * 1. **Bloqueo**: desde que el ViewModel se destruye ([claim]) hasta que la sesión queda cerrada y su hilo de
 *    guardado terminó, la huella está en [BlockedFingerprints]: nadie puede abrir ese juego ni restaurar sus copias
 *    (Ajustes › Partidas), así que ninguna escritura tardía de la sesión pendiente puede pisar una restauración ni
 *    a una sesión nueva.
 * 2. **Reintento**: si el rescate acaba en [RescueOutcome.KeptOpen] (el disco falla), reintenta
 *    [GameSession.rescueExit] con backoff exponencial acotado ([initialDelayMs] … [maxDelayMs]).
 * 3. **Cierre**: cuando el guardado queda confirmado, `rescueExit` guarda AUTO y cierra la sesión; entonces se libera la
 *    huella. Sin fugas: el hilo del registro solo existe mientras haya trabajo (muere tras [keepAliveMs] inactivo).
 */
class OrphanSessionRegistry(
    private val blocked: BlockedFingerprints = BlockedFingerprints.shared,
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    keepAliveMs: Long = 2_000,
) {
    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "pocketgb-orphans").apply { isDaemon = true }
    }.apply {
        setKeepAliveTime(keepAliveMs, TimeUnit.MILLISECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
    }

    /** Hilos vivos del registro (tests: tras cerrar todo debe volver a 0). */
    internal val liveThreads: Int get() = executor.poolSize

    /** Marca la huella de [game] como pendiente ANTES de lanzar el rescate (sin ventana en la que nadie la bloquee). */
    fun claim(game: GameSession) {
        blocked.acquire(game.fingerprint)
    }

    /** Cierra el ciclo de un [claim] con el resultado del primer rescate. */
    fun settle(game: GameSession, outcome: RescueOutcome) {
        when (outcome) {
            RescueOutcome.Closed -> afterClosed(game)
            RescueOutcome.KeptOpen -> schedule(game, initialDelayMs)
        }
    }

    private fun schedule(game: GameSession, delayMs: Long) {
        try {
            executor.schedule({ attempt(game, delayMs) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // El registro nunca se cierra; por si acaso, la sesión no se libera (sigue bloqueada, no se pierde nada).
        }
    }

    private fun attempt(game: GameSession, delayMs: Long) {
        val outcome = try {
            // Un solo intento por turno, sin reescribir el estado de rescate (ya está escrito).
            game.rescueExit(attempts = 1, retryDelayMs = 0, writeRescueState = false)
        } catch (_: Throwable) {
            RescueOutcome.KeptOpen
        }
        if (outcome == RescueOutcome.Closed || game.isClosed) afterClosed(game) else schedule(game, minOf(delayMs * 2, maxDelayMs))
    }

    /** La sesión está cerrada: la huella solo se libera cuando su hilo de guardado terminó (ya no puede escribir). */
    private fun afterClosed(game: GameSession) {
        val result = game.tryClose()
        if (result is CloseResult.SaveThreadStuck) {
            Thread({
                try { game.awaitSaveThreadExit() } catch (_: InterruptedException) { return@Thread }
                blocked.release(game.fingerprint)
            }, "pocketgb-orphan-reaper").apply { isDaemon = true }.start()
        } else {
            blocked.release(game.fingerprint)
        }
    }

    companion object {
        /** Registro de la app. */
        val shared = OrphanSessionRegistry()
    }
}
