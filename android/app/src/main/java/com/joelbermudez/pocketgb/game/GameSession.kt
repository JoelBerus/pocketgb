package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.emulator.CoreError
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.RomInfo
import com.joelbermudez.pocketgb.emulator.SessionError
import com.joelbermudez.pocketgb.emulator.SessionState
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
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
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
}

sealed interface ExitResult {
    /** La partida local está a salvo y la sesión se cerró. */
    data object Clean : ExitResult

    /** No se pudo guardar la partida local: la sesión sigue abierta y en pausa. */
    class LocalSaveFailed(val error: Throwable) : ExitResult
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
) : AutoCloseable {
    private val mutableProblem = MutableStateFlow<Throwable?>(null)

    /** Error de guardado local sin resolver (escritura fallida o pendiente), o `null`. */
    val saveProblem: StateFlow<Throwable?> = mutableProblem.asStateFlow()

    private val closedFlag = AtomicBoolean(false)
    val isClosed: Boolean get() = closedFlag.get()

    val fingerprint: String get() = info.fingerprintHex

    /** `true` si la SRAM de esta sesión se persiste. */
    val persists: Boolean get() = target != null

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
        if (session.state.value == SessionState.Running) session.pause()
        return flushNow()
    }

    private fun flushNow(): FlushResult {
        val current = session.state.value
        if (isClosed || current == SessionState.Closed || current == SessionState.New) return FlushResult.Unchanged
        return coordinator.flushSync()
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
        if (session.state.value == SessionState.Paused) session.resume()
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
                try {
                    session.loadStateRaw(previous.bytes)
                } catch (_: Exception) {
                    // Sin más remedio: el estado previo salió del propio núcleo hace un instante.
                }
                // Una escritura rezagada pudo dejar en disco la SRAM del estado rechazado: se corrige luego.
                coordinator.requestFlush()
                throw StateError.SaveFailed((result as? FlushResult.Failed)?.error)
            }
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
        if (session.state.value == SessionState.Running) session.pause()
        if (coordinator.hasTarget) {
            val result = flushNow()
            if (!result.isSafe && !force) {
                return ExitResult.LocalSaveFailed(errorOf(result))
            }
        }
        // AUTO al salir: tras un flush correcto es la continuación exacta; con `force` es el rescate (J6).
        if (session.state.value == SessionState.Paused) {
            try {
                saveState(StateSlot.AUTO)
            } catch (_: Exception) {
            }
        }
        close()
        return ExitResult.Clean
    }

    private fun errorOf(result: FlushResult): Throwable = when (result) {
        is FlushResult.Failed -> result.error
        FlushResult.TimedOut -> coordinator.pendingError ?: TimeoutException("El guardado no terminó a tiempo")
        else -> IllegalStateException("Sin error")
    }

    /** Cierra sin guardar nada más (best-effort, p. ej. `onCleared`). Idempotente; une el hilo de guardado antes (I3). */
    override fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        coordinator.close()
        session.close()
    }
}

/** Mejor esfuerzo al destruirse el ViewModel: pausa, intenta vaciar y cierra. Nunca lanza. */
fun GameSession.closeBestEffort() {
    try {
        if (!isClosed) {
            if (session.state.value == SessionState.Running) session.pause()
            exit(force = true)
        }
    } catch (_: Throwable) {
    } finally {
        close()
    }
}
