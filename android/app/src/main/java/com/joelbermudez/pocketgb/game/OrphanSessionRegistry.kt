package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.saves.CloseResult
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Dueño, a NIVEL DE APLICACIÓN, de las sesiones cuyo ViewModel se destruyó (A5 2ª vuelta, A5V2-H1). Sobrevive a
 * cualquier ViewModel y cumple tres cosas:
 *
 * 1. **Propiedad**: la [GameSession] posee el lease exclusivo de su huella toda su vida ([com.joelbermudez.pocketgb.saves.FingerprintOwnership]),
 *    así que al destruirse el ViewModel el MISMO token pasa al registro sin soltarse ni volver a adquirirse (no hay
 *    hueco: A5V3-H1). Hasta que la sesión queda cerrada y su hilo de guardado terminó nadie puede abrir ese juego ni
 *    restaurar sus copias; la propia sesión suelta el lease al cerrarse de verdad (también si su hilo reaper es
 *    interrumpido o falla).
 * 2. **Reintento**: si el rescate acaba en [RescueOutcome.KeptOpen] (el disco falla), reintenta
 *    [GameSession.rescueExit] con backoff exponencial acotado ([initialDelayMs] … [maxDelayMs]).
 * 3. **Cierre**: cuando el guardado queda confirmado, `rescueExit` guarda AUTO y cierra la sesión. Sin fugas: el hilo del
 *    registro solo existe mientras haya trabajo (muere tras [keepAliveMs] inactivo).
 */
class OrphanSessionRegistry(
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

    private val owned = java.util.concurrent.ConcurrentHashMap.newKeySet<GameSession>()

    /** Sesiones que el registro tiene en su poder (tests). */
    internal val ownedCount: Int get() = owned.size

    /**
     * Recibe la sesión de un ViewModel destruido ANTES de lanzar el rescate. No adquiere nada: la sesión ya posee su
     * lease (el mismo token sigue vigente, sin ventana).
     */
    fun claim(game: GameSession) {
        owned.add(game)
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

    /** La sesión está cerrada: `tryClose` suelta el lease cuando el hilo de guardado terminó de verdad (o lo hace su reaper). */
    private fun afterClosed(game: GameSession) {
        try {
            game.tryClose()
        } finally {
            owned.remove(game)
        }
    }

    companion object {
        /** Registro de la app. */
        val shared = OrphanSessionRegistry()
    }
}
