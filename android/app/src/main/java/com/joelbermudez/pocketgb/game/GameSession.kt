package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.emulator.CoreError
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.RomInfo
import com.joelbermudez.pocketgb.emulator.SessionError
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.saves.CloseResult
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.FlushResult
import com.joelbermudez.pocketgb.saves.FramePng
import com.joelbermudez.pocketgb.saves.SaveCoordinator
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.SaveTarget
import com.joelbermudez.pocketgb.saves.SramFlushPolicy
import com.joelbermudez.pocketgb.saves.SramSource
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.saves.isSafe
import com.joelbermudez.pocketgb.saves.saf.MirrorDisabledReason
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fallos de los estados guardados. Cada caso tiene su texto y recuperación en la UI. */
sealed class StateError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Guardar o cargar exige la sesión en pausa. */
    class NotParked : StateError("El juego debe estar en pausa para guardar o cargar un estado.")

    /** El núcleo rechazó el estado (dañado, de otro ROM, versión) o falló al guardarlo. */
    class Core(val error: CoreError) : StateError(error.message ?: "El núcleo rechazó el estado.", error)

    /** No se pudo leer o escribir el archivo del estado. */
    class Io(cause: Throwable) : StateError("No se pudo acceder al estado guardado: ${cause.message}", cause)

    /**
     * Cargar el estado obligaba a guardar la partida contenida y no se pudo: el núcleo vuelve a como estaba
     * y la partida actual no ha cambiado.
     */
    class SaveFailed(cause: Throwable?) :
        StateError("La partida actual no ha cambiado: no se pudo guardar la del estado cargado.", cause)

    /**
     * Cargar el estado no se pudo completar y el núcleo volvió a como estaba, pero NO se pudo confirmar en disco
     * que la partida vuelve a ser la de antes (una escritura rezagada puede haber dejado la del estado
     * rechazado): el guardado queda pendiente y se reintenta. No se afirma que "no ha cambiado".
     */
    class SavePending(cause: Throwable?) : StateError(
        "El estado no se cargó. El juego volvió a como estaba, pero el guardado de la partida quedó pendiente de confirmar; se reintentará.",
        cause,
    )

    /**
     * Ni siquiera se pudo devolver el núcleo a como estaba: lo que hay en memoria ya no es de fiar y NO se
     * escribe nunca en disco. La sesión deja de guardar (se avisa); salir y volver a abrir recupera la partida.
     *
     * [restored] = la partida anterior quedó confirmada como principal en disco (se pudo drenar la escritura en
     * vuelo y reescribirla). Si es `false` NO se afirma que siga intacta: la reparación continúa en segundo plano y
     * el juego no se puede reabrir hasta que termine.
     */
    class RollbackFailed(cause: Throwable?, val restored: Boolean = true) : StateError(
        if (restored) {
            "No se pudo volver a la partida anterior: este juego ya no guardará su progreso. Sal del juego y ábrelo de nuevo (la partida guardada sigue intacta)."
        } else {
            "No se pudo volver a la partida anterior: este juego ya no guardará su progreso. La partida guardada se está restaurando en segundo plano; el juego no se podrá abrir de nuevo hasta que termine."
        },
        cause,
    )
}

sealed interface ExitResult {
    /** La partida local está a salvo y la sesión se cerró. */
    data object Clean : ExitResult

    /** No se pudo guardar la partida local: la sesión sigue abierta y en pausa. */
    class LocalSaveFailed(val error: Throwable) : ExitResult
}

/** Qué pasó al rescatar la sesión de un ViewModel destruido ([GameSession.rescueExit]). */
sealed interface RescueOutcome {
    /** Se guardó (o no había nada que guardar) y la sesión se cerró. */
    data object Closed : RescueOutcome

    /** No se pudo guardar tras reintentar: la sesión queda abierta y en pausa y el estado de rescate escrito. */
    data object KeptOpen : RescueOutcome
}

/** Avisos no bloqueantes durante la partida (el juego sigue: la copia local ya está segura). */
sealed interface GameEvent {
    /** El espejo junto a la ROM no se pudo actualizar; se reintenta solo. */
    data object MirrorTrouble : GameEvent

    /** El espejo se desactivó durante la partida (J2 u otro motivo). */
    data class MirrorDisabled(val reason: MirrorDisabledReason) : GameEvent
}

/** Bus de avisos creado antes que la sesión: el lanzador lo comparte con el espejo SAF. */
class GameEvents {
    private val mutable = MutableSharedFlow<GameEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<GameEvent> = mutable.asSharedFlow()
    fun post(event: GameEvent) {
        mutable.tryEmit(event)
    }
}

/**
 * Una partida abierta: dueña de la [EmulatorSession], del [SaveCoordinator] (hilo `pocketgb-saves`), del
 * destino de guardado y de los estados (SPEC §2.2, §5.2–5.4, §6). Ninguna otra pieza cierra la sesión.
 *
 * - Sin [target] (tamaño de `.sav` incorrecto, J10, o cartucho sin batería) los estados funcionan pero la
 *   SRAM no se persiste.
 * - Los estados son operaciones con la sesión aparcada (pausa); el PNG y la escritura atómica ocurren en el
 *   hilo de guardado.
 * - Un fallo local NUNCA se ignora al salir: [exit] devuelve [ExitResult.LocalSaveFailed] y la sesión sigue
 *   abierta, a menos que se fuerce (con rescate previo en el estado AUTO, J6).
 */
class GameSession(
    val session: EmulatorSession,
    val info: RomInfo,
    private val states: StateStore,
    private val target: SaveTarget? = null,
    baseline: ByteArray? = null,
    val warning: SaveLoadWarning? = null,
    val entryId: String? = null,
    val events: GameEvents = GameEvents(),
    policy: SramFlushPolicy = SramFlushPolicy({ System.nanoTime() / 1_000_000 }),
    tickMs: Long = 100,
    flushTimeoutMs: Long = 3_000,
    autoTick: Boolean = true,
    /** Plazo de gracia del cierre del hilo de guardado antes de interrumpirlo (I3). */
    private val closeGraceMs: Long = 10_000,
    private val closeKillWaitMs: Long = 5_000,
    /**
     * Propiedad exclusiva de la huella (A5V3-H1), adquirida por el lanzador ANTES de tocar ningún archivo. La sesión la
     * posee toda su vida (también como huérfana y durante una reparación) y la suelta cuando está cerrada de verdad
     * (hilo de guardado terminado) y sin reparación en curso. `null` solo en pruebas sin lanzador.
     */
    private val lease: FingerprintOwnership.Lease? = null,
    /** Cuánto espera [loadState] a que la reparación de la partida anterior quede confirmada antes de informar. */
    private val repairWaitMs: Long = 3_000,
    /** Plazo corto de los vaciados del hilo principal tras un [FlushResult.TimedOut] reciente (anti-ANR). */
    private val shortFlushMs: Long = 500,
) : AutoCloseable {
    private val mutableProblem = MutableStateFlow<Throwable?>(null)

    /** Error de guardado local sin resolver (escritura fallida o pendiente), o `null`. */
    val saveProblem: StateFlow<Throwable?> = mutableProblem.asStateFlow()

    /**
     * Cuántos "usos" mantienen vivo el lease: 1 por la propia sesión (se suelta al cerrarse) + 1 por cada reparación de
     * la partida anterior en curso. Al llegar a 0 se libera el lease (idempotente).
     */
    private val leaseHolds = java.util.concurrent.atomic.AtomicInteger(1)

    private fun holdLease() {
        leaseHolds.incrementAndGet()
    }

    private fun dropLease() {
        if (leaseHolds.decrementAndGet() == 0) lease?.close()
    }

    /** `true` mientras la sesión conserva la propiedad exclusiva de su huella (pruebas). */
    val holdsLease: Boolean get() = lease?.isReleased == false

    private val closedFlag = AtomicBoolean(false)
    val isClosed: Boolean get() = closedFlag.get()

    @Volatile private var closeResult: CloseResult? = null

    /** Último plazo agotado de un vaciado del hilo principal (ns), o 0. */
    @Volatile private var lastTimeoutNs = 0L

    val fingerprint: String get() = info.fingerprintHex

    /** Hay un estado de rescate de una salida anterior con fallo de guardado (se avisa al abrir). */
    val hasRescueState: Boolean get() = try { states.hasRescue() } catch (_: Exception) { false }

    /** `true` si la SRAM de esta sesión se persiste. */
    val persists: Boolean get() = coordinator.hasTarget

    private val coordinator = SaveCoordinator(
        source = object : SramSource {
            override fun dirtySeq(): Long = session.sramDirtySequence()
            override fun copy(): ByteArray = session.copySram()
        },
        target = target,
        baseline = baseline,
        policy = policy,
        tickMs = tickMs,
        flushTimeoutMs = flushTimeoutMs,
        autoTick = autoTick,
        onStatus = { mutableProblem.value = it },
        onMirrorTrouble = { events.post(GameEvent.MirrorTrouble) },
    )

    /** Duraciones (ns) de los vaciados síncronos de esta sesión (para p50/p99). */
    fun flushDurationsNanos(): List<Long> = coordinator.flushDurationsNanos()

    val state: StateFlow<SessionState> get() = session.state

    // ------------------------------------------------------------------ transiciones

    fun start() {
        session.start()
    }

    /**
     * Pausa nativa y vaciado síncrono acotado (3 s, J5). Idempotente: sirve igual desde `ON_PAUSE`,
     * `ON_STOP` y la pérdida de foco de audio; repetirlo reintenta un guardado pendiente.
     */
    fun pause(): FlushResult {
        if (isClosed) return FlushResult.Unchanged
        try {
            pauseIfRunning()
        } catch (_: CoreError.Closed) {
            return FlushResult.Unchanged // se cerró justo ahora (salida en curso): nada que pausar ni vaciar
        }
        return flushNow(mainThread = true)
    }

    /** Serializa "mirar el estado y pausar": `ON_PAUSE` (principal) y una salida (E/S) no pueden pisarse. */
    private val transitions = java.util.concurrent.locks.ReentrantLock()

    private fun pauseIfRunning() = transitions.withLock {
        if (session.state.value == SessionState.Running) session.pause()
    }

    private fun resumeIfPaused() = transitions.withLock {
        if (session.state.value == SessionState.Paused) session.resume()
    }

    /**
     * Vaciado síncrono. Desde el hilo principal ([mainThread]) el plazo se acorta (500 ms) mientras haya un
     * plazo agotado reciente (10 s): `ON_PAUSE` y `ON_STOP` seguidos con un disco bloqueado no suman 6 s de
     * hilo principal (ANR); el guardado sigue pendiente y reintentándose en su hilo.
     */
    private fun flushNow(mainThread: Boolean = false): FlushResult {
        val current = session.state.value
        if (isClosed || current == SessionState.Closed || current == SessionState.New) return FlushResult.Unchanged
        val recentTimeout = lastTimeoutNs != 0L && System.nanoTime() - lastTimeoutNs < RECENT_TIMEOUT_NS
        val result = if (mainThread && recentTimeout) coordinator.flushSync(shortFlushMs) else coordinator.flushSync()
        lastTimeoutNs = if (result == FlushResult.TimedOut) System.nanoTime() else 0L
        return result
    }

    /**
     * Al volver a primer plano con un guardado pendiente (plazo agotado o escritura fallida): programa el
     * reintento sin reanudar el juego (J5). El siguiente vaciado síncrono (pausa, salida) también lo reintenta.
     */
    fun retryPendingSave() {
        if (!isClosed && coordinator.pendingError != null) coordinator.requestFlush()
    }

    fun resume() {
        if (isClosed) return
        resumeIfPaused()
        if (coordinator.pendingError != null) coordinator.requestFlush()
    }

    // ------------------------------------------------------------------ estados

    /** Ranuras ocupadas. Bloquea (lee archivos): llamar fuera del hilo principal. */
    fun states(): Map<StateSlot, StateStore.Entry> = coordinator.runOnSaveThread { states.entries() }

    /** Guarda el estado actual en [slot]. La sesión debe estar en pausa. */
    fun saveState(slot: StateSlot) {
        val saved = try {
            session.saveState()
        } catch (_: SessionError.NotParked) {
            throw StateError.NotParked()
        } catch (error: CoreError) {
            throw StateError.Core(error)
        }
        try {
            coordinator.runOnSaveThread { states.save(saved.bytes, FramePng.encode(saved.pixels), slot) }
        } catch (error: IOException) {
            throw StateError.Io(error)
        } catch (error: TimeoutException) {
            throw StateError.Io(error)
        }
    }

    /**
     * Carga [slot] con la sesión en pausa. Si [saveCurrentFirst], antes guarda el estado actual en AUTO (si eso
     * falla, no se carga nada). Después persiste la SRAM del estado cargado: si no se puede, el núcleo vuelve
     * a como estaba y se lanza [StateError.SaveFailed] (SPEC §5.4).
     */
    fun loadState(slot: StateSlot, saveCurrentFirst: Boolean = slot != StateSlot.AUTO) {
        if (session.state.value != SessionState.Paused) throw StateError.NotParked()
        val data = try {
            coordinator.runOnSaveThread { states.load(slot) }
        } catch (error: IOException) {
            throw StateError.Io(error)
        } catch (error: TimeoutException) {
            throw StateError.Io(error)
        }
        if (saveCurrentFirst) saveState(StateSlot.AUTO)
        val previous = try {
            session.saveState()
        } catch (_: SessionError.NotParked) {
            throw StateError.NotParked()
        } catch (error: CoreError) {
            throw StateError.Core(error)
        }
        // Copia INDEPENDIENTE en memoria de la partida (SRAM) de antes de cargar nada: si el rollback del núcleo falla,
        // es lo único fiable con lo que reponer la partida principal en disco (A5V2-H3).
        val sramBefore = if (coordinator.hasTarget) {
            try { session.copySram() } catch (error: CoreError) { throw StateError.Core(error) }
        } else {
            null
        }
        try {
            session.loadStateRaw(data)
        } catch (error: CoreError) {
            throw StateError.Core(error) // se rechaza antes de tocar nada
        } catch (_: SessionError.NotParked) {
            throw StateError.NotParked()
        }
        if (coordinator.hasTarget) {
            val result = coordinator.flushSync()
            if (!result.isSafe) {
                val cause = (result as? FlushResult.Failed)?.error ?: coordinator.pendingError
                val restored = try {
                    session.loadStateRaw(previous.bytes)
                    true
                } catch (_: Exception) {
                    false
                }
                if (!restored) {
                    // Lo que hay en el núcleo ya no es de fiar: no se escribe NUNCA más desde él. Pero una escritura B
                    // (la SRAM del estado rechazado) puede seguir en vuelo y terminar como `.sav` principal: se
                    // repone la partida anterior A desde la copia en memoria, en el MISMO hilo (detrás de B, FIFO) y
                    // con el escritor atómico (B queda como backup y A como principal). Hasta confirmarlo, la huella
                    // queda bloqueada para reabrir/restaurar.
                    coordinator.disablePersistence(StateError.RollbackFailed(cause, restored = false))
                    val confirmed = if (sramBefore != null && target != null) repairPrevious(target, sramBefore) else true
                    throw StateError.RollbackFailed(cause, confirmed)
                }
                // Transaccional: una escritura rezagada (plazo agotado) pudo dejar en disco la SRAM del estado
                // rechazado. Se encola una BARRERA en el MISMO hilo, detrás de ella: copia la SRAM ya revertida y
                // la escribe si hace falta. Solo tras confirmarla se puede decir "la partida no ha cambiado".
                val barrier = coordinator.flushSync()
                if (barrier.isSafe) throw StateError.SaveFailed(cause)
                coordinator.requestFlush()
                throw StateError.SavePending(cause)
            }
        }
    }

    /**
     * Repone [sram] (la partida de antes de cargar el estado rechazado) como `.sav` principal, tras cualquier
     * escritura en vuelo, y devuelve `true` si quedó confirmado en [repairWaitMs]. Si no, la reparación SIGUE en un
     * hilo propio hasta lograrlo (con backoff acotado) y mientras tanto la huella está bloqueada. Nunca se pierde:
     * la tarea ya encolada corre cuando B termine; si el hilo de guardado se cierra sin ejecutarla, se espera a
     * que salga de verdad (B ya no puede escribir) y se escribe directamente.
     */
    private fun repairPrevious(target: SaveTarget, sram: ByteArray): Boolean {
        // La sesión ya posee la huella; la reparación la mantiene aunque la sesión se cierre antes de terminar.
        holdLease()
        val done = java.util.concurrent.CountDownLatch(1)
        val first = try { coordinator.submitOnSaveThread { target.persistLocal(sram) } } catch (_: IOException) { null }
        Thread({
            var future: java.util.concurrent.Future<Unit>? = first
            var delay = REPAIR_BACKOFF_START_MS
            try {
                while (true) {
                    try {
                        val pending = future
                        if (pending != null) {
                            try {
                                pending.get(REPAIR_POLL_MS, TimeUnit.MILLISECONDS)
                                return@Thread
                            } catch (_: TimeoutException) {
                                // La tarea encolada pudo descartarse en un cierre forzado: no se espera para siempre.
                                if (coordinator.isFinished && !pending.isDone) future = null
                                continue
                            } catch (error: java.util.concurrent.ExecutionException) {
                                throw error.cause ?: error
                            }
                        } else {
                            // Sin cola (cerrada): con el hilo de guardado ya salido, ningún B rezagado puede escribir.
                            coordinator.awaitThreadExit()
                            target.persistLocal(sram)
                            return@Thread
                        }
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (error: Throwable) {
                        Log.w(TAG, "Reparación de la partida anterior fallida; se reintenta", error)
                        try { Thread.sleep(delay) } catch (_: InterruptedException) { return@Thread }
                        delay = minOf(delay * 2, REPAIR_BACKOFF_MAX_MS)
                        future = try { coordinator.submitOnSaveThread { target.persistLocal(sram) } } catch (_: IOException) { null }
                    }
                }
            } finally {
                // Solo se suelta la huella cuando la reparación terminó (éxito) o el hilo murió interrumpido (y la sesión
                // ya está cerrada).
                try { dropLease() } finally { done.countDown() }
            }
        }, "pocketgb-save-repair").apply { isDaemon = true }.start()
        return try { done.await(repairWaitMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun deleteState(slot: StateSlot) {
        try {
            coordinator.runOnSaveThread { states.delete(slot) }
        } catch (error: IOException) {
            throw StateError.Io(error)
        } catch (error: TimeoutException) {
            throw StateError.Io(error)
        }
    }

    // ------------------------------------------------------------------ salida

    /**
     * Sale de la partida: pausa, vacía la SRAM y, si quedó a salvo, guarda el estado AUTO (su fallo no impide
     * salir) y cierra la sesión. Si la copia local NO está a salvo y no se [force]a, devuelve
     * [ExitResult.LocalSaveFailed] y deja la sesión abierta y en pausa. Con [force] (decisión explícita de
     * Joel, con doble confirmación) intenta antes un estado AUTO de rescate (J6) y cierra.
     */
    fun exit(force: Boolean = false): ExitResult {
        if (isClosed) return ExitResult.Clean
        pauseIfRunning()
        var flushSafe = true
        if (coordinator.hasTarget) {
            val result = flushNow()
            flushSafe = result.isSafe
            if (!flushSafe && !force) {
                return ExitResult.LocalSaveFailed(errorOf(result))
            }
        }
        if (session.state.value == SessionState.Paused) {
            if (flushSafe) {
                // AUTO al salir: la continuación exacta (su fallo no impide salir).
                try { saveState(StateSlot.AUTO) } catch (_: Exception) {}
            } else {
                saveRescueState() // J6: ranura de rescate aparte, que nunca pisa nada en silencio
            }
        }
        tryClose()
        return ExitResult.Clean
    }

    /** Estado de rescate (J6) en su ranura propia, directo (no depende del hilo de guardado, que pudo atascarse). */
    private fun saveRescueState() {
        try {
            val saved = session.saveState()
            states.saveRescue(saved.bytes, FramePng.encode(saved.pixels))
        } catch (_: Exception) {
        }
    }

    /**
     * Cierre de una sesión cuyo ViewModel se destruyó (no hay UI que confirme nada). NUNCA fuerza una salida con
     * riesgo en silencio: reintenta el vaciado [attempts] veces; si logra confirmar, guarda AUTO y cierra. Si no,
     * escribe el estado de rescate y deja la sesión abierta en pausa (su hilo de guardado sigue reintentando).
     * Quien recibe [RescueOutcome.KeptOpen] debe entregar la sesión a [OrphanSessionRegistry]: sin dueño, esa sesión
     * nunca se cerraría. Bloquea: llamar fuera del hilo principal.
     */
    fun rescueExit(attempts: Int = 10, retryDelayMs: Long = 1_000, writeRescueState: Boolean = true): RescueOutcome {
        repeat(attempts) { attempt ->
            if (isClosed) return RescueOutcome.Closed
            val safe = !coordinator.hasTarget || flushNow().isSafe
            if (safe) {
                if (session.state.value == SessionState.Paused) {
                    try { saveState(StateSlot.AUTO) } catch (_: Exception) {}
                }
                tryClose()
                return RescueOutcome.Closed
            }
            coordinator.requestFlush()
            if (attempt < attempts - 1) Thread.sleep(retryDelayMs)
        }
        // El rescate no se repite en los reintentos automáticos del registro de huérfanas ([writeRescueState] = false).
        if (writeRescueState && session.state.value == SessionState.Paused) saveRescueState()
        return RescueOutcome.KeptOpen
    }

    private fun errorOf(result: FlushResult): Throwable = when (result) {
        is FlushResult.Failed -> result.error
        FlushResult.TimedOut -> coordinator.pendingError ?: TimeoutException("El guardado no terminó a tiempo")
        else -> IllegalStateException("Sin error")
    }

    /**
     * Cierra sin guardar nada más (I3): marca cerrado el guardado (ninguna copia nativa nueva), confirma que el hilo
     * de guardado terminó de verdad y solo entonces libera el handle nativo. Si el hilo sigue vivo (operación de
     * archivo que no responde) devuelve [CloseResult.SaveThreadStuck] y NO libera: un "reaper" lo hará cuando el
     * hilo salga. Idempotente.
     */
    fun tryClose(): CloseResult {
        if (!closedFlag.compareAndSet(false, true)) return closeResult ?: CloseResult.Closed
        val result = try {
            coordinator.shutdown(closeGraceMs, closeKillWaitMs)
        } catch (error: Throwable) {
            dropLease()
            throw error
        }
        closeResult = result
        when (result) {
            CloseResult.Closed -> try { session.close() } finally { dropLease() }
            is CloseResult.SaveThreadStuck -> {
                Log.w(TAG, "El hilo de guardado no terminó (${result.threadName}); el handle nativo se libera cuando salga")
                Thread({
                    // La propiedad de la huella NUNCA se suelta mientras el hilo de guardado siga vivo: una
                    // interrupción no es una confirmación de que la escritura antigua terminó (A5V4-H1). Se sigue
                    // esperando y se restaura la marca de interrupción al final.
                    var interrupted = false
                    try {
                        while (true) {
                            try {
                                coordinator.awaitThreadExit()
                                break
                            } catch (_: InterruptedException) {
                                interrupted = true
                            }
                        }
                        try { session.close() } finally { dropLease() }
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt()
                    }
                }, "pocketgb-save-reaper").apply { isDaemon = true }.start()
            }
        }
        return result
    }

    /** Bloquea hasta que el hilo de guardado haya salido de verdad (tras [tryClose] con [CloseResult.SaveThreadStuck]). */
    fun awaitSaveThreadExit() = coordinator.awaitThreadExit()

    /** Cierra sin guardar nada más. Idempotente; ver [tryClose]. */
    override fun close() {
        tryClose()
    }

    private companion object {
        const val TAG = "GameSession"
        const val REPAIR_POLL_MS = 250L
        const val REPAIR_BACKOFF_START_MS = 200L
        const val REPAIR_BACKOFF_MAX_MS = 5_000L
        const val RECENT_TIMEOUT_NS = 10_000_000_000L
    }
}
