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
import com.joelbermudez.pocketgb.saves.MomentStore
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

    /** N6: el momento no tiene estado del núcleo (migrado sin él o solo partida): se recupera su partida desde el detalle. */
    class NoState : StateError("Este momento no tiene un estado que cargar.")

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
    /** Cierre del hilo de guardado; inyectable en pruebas para simular un fallo en pleno cierre (A5V6-H2). */
    private val shutdownCoordinator: (SaveCoordinator, Long, Long) -> CloseResult =
        { coordinator, graceMs, killWaitMs -> coordinator.shutdown(graceMs, killWaitMs) },
    /** Encolado de la reparación en el hilo de guardado; inyectable en pruebas (A5V6-H1). */
    private val repairSubmit: (SaveCoordinator, () -> Unit) -> java.util.concurrent.Future<Unit> =
        { coordinator, block -> coordinator.submitOnSaveThread(block) },
    /** Fábrica del hilo de reparación; inyectable en pruebas para simular un fallo al arrancarlo (A5V6-H3). */
    private val repairThreadFactory: (Runnable, String) -> Thread =
        { body, name -> Thread(body, name).apply { isDaemon = true } },
    /** Nombre del juego en la pausa: el alias si el usuario lo renombró (A9), o el título de la cabecera. */
    val title: String = info.title,
    /**
     * Hay partida que guardar pero esta sesión no la cargó ni la guarda (`.sav` de tamaño incorrecto o ilegible, J10).
     * El AUTO que hubiera antes puede ser la única copia de un progreso: la primera escritura del AUTO de esta sesión
     * lo aparta en vez de pisarlo (A9-H2).
     */
    private val unsavedCartridge: Boolean = false,
    /** N6: momentos y anillo «Antes de cargar» de esta huella (`null` en pruebas antiguas sin momentos). */
    private val moments: MomentStore? = null,
    /** N6 (ND13): configuración con la que se abrió (modelo, paleta; en GBA, tipo de partida, reloj y BIOS). */
    val momentConfig: Map<String, String> = emptyMap(),
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

    /**
     * Portada (A6-L3, K9): si está puesto, [tryClose] le entrega el último fotograma (sesión aparcada) tras el
     * vaciado correcto y antes de liberar el handle nativo. Solo lee; un fallo nunca altera el [CloseResult].
     */
    @Volatile var parkedFrameCallback: ((IntArray) -> Unit)? = null

    /** Último plazo agotado de un vaciado del hilo principal (ns), o 0. */
    @Volatile private var lastTimeoutNs = 0L

    val fingerprint: String get() = info.fingerprintHex

    /** Hay un estado de rescate de una salida anterior con fallo de guardado (se avisa al abrir). */
    val hasRescueState: Boolean get() = try { states.hasRescue() } catch (_: Exception) { false }

    /** `true` si la SRAM de esta sesión se persiste. */
    val persists: Boolean get() = coordinator.hasTarget

    /**
     * El núcleo es de fiar para guardar un estado AUTO: hay destino y su persistencia no se desactivó tras un rollback
     * fallido (o la sesión nunca tuvo destino). Con un núcleo dudoso no se escribe AUTO (A9-H4).
     */
    private val coreTrusted: Boolean get() = target == null || coordinator.hasTarget

    /** Esta sesión ya escribió su propio AUTO (los siguientes lo sustituyen; ver [unsavedCartridge]). */
    @Volatile private var autoWritten = false

    /**
     * El AUTO es ahora el «deshacer» de un «Guardar actual y cargar» (A9-H3): hasta salir, el AUTO de segundo plano no
     * lo pisa. La salida lo escribe como antes de A9 (el anillo «Antes de cargar» llegará en N6).
     */
    @Volatile var autoHoldsLoadUndo = false
        private set

    /**
     * N8: tamaño del `.sav` al abrir. Una EEPROM de GBA sin ajuste mide 512 B hasta que el `.sav` o la primera DMA
     * confirman 8 KiB; si la DMA lo confirma solo leyendo, el juego no «guarda» y no habría `.sav` de 8 KiB, así que el
     * estado automático de esa sesión (ya con 8 KiB) daría `StateConfig` al continuar (nota de la auditoría N8 nativo).
     * Por eso un cambio de tamaño cuenta como un guardado más: se escribe la partida con su tamaño nuevo.
     */
    private val openedSaveSize: Int = try { session.sramSaveSize } catch (_: RuntimeException) { 0 }
    // Invariante (auditoría N8 Kotlin, H1): dentro de una sesión el tamaño solo crece (EEPROM 512 B → 8 KiB, una vez;
    // confirmado, el núcleo no lo vuelve a cambiar), así que el salto de `dirtySeq` es un único escalón y la secuencia
    // nunca retrocede. Si algún día el tamaño pudiera volver al de apertura, el escalón se desharía y el coordinador lo
    // vería como «otro cambio» (sigue siendo seguro: solo provoca un guardado de más, nunca uno de menos).

    private val coordinator = SaveCoordinator(
        source = object : SramSource {
            override fun dirtySeq(): Long =
                session.sramDirtySequence() + if (session.sramSaveSize != openedSaveSize) SIZE_CHANGE_SEQ else 0L
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

    /* Ajustes en caliente (A6-L2): delegan en la sesión como `setSpeed`; no tocan la ruta de guardado ni de cierre. */
    fun setVolume(gain: Float) = session.setVolume(gain)

    fun setScaleMode(mode: com.joelbermudez.pocketgb.emulator.ScaleMode) = session.setScaleMode(mode)

    /** Solo surte efecto con la ROM en compatibilidad CGB ([RomInfo.cgbCompat]); si no, no hace nada. */
    fun setCompatPalette(id: Int) {
        if (info.cgbCompat) session.setCompatPalette(id)
    }

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
        val preserveForeignAuto = slot == StateSlot.AUTO && unsavedCartridge && !autoWritten
        try {
            coordinator.runOnSaveThread {
                if (preserveForeignAuto) states.setAsideAuto() // A9-H2: el AUTO de antes de esta sesión no se pisa
                states.save(saved.bytes, FramePng.encode(saved.pixels), slot)
            }
        } catch (error: IOException) {
            throw StateError.Io(error)
        } catch (error: TimeoutException) {
            throw StateError.Io(error)
        }
        if (slot == StateSlot.AUTO) autoWritten = true
    }

    /**
     * Carga [slot] con la sesión en pausa. Si [saveCurrentToAuto] (y [slot] no es AUTO), antes guarda el estado actual en AUTO (si eso
     * falla, no se carga nada). Después persiste la SRAM del estado cargado: si no se puede, el núcleo vuelve
     * a como estaba y se lanza [StateError.SaveFailed] (SPEC §5.4).
     */
    fun loadState(slot: StateSlot, saveCurrentToAuto: Boolean = true) {
        if (session.state.value != SessionState.Paused) throw StateError.NotParked()
        val data = try {
            coordinator.runOnSaveThread { states.load(slot) }
        } catch (error: IOException) {
            throw StateError.Io(error)
        } catch (error: TimeoutException) {
            throw StateError.Io(error)
        }
        if (saveCurrentToAuto && slot != StateSlot.AUTO) {
            saveState(StateSlot.AUTO)
            autoHoldsLoadUndo = true // A9-H3: desde aquí el AUTO es el «deshacer» de esta carga
        }
        val previous = try {
            session.saveState()
        } catch (_: SessionError.NotParked) {
            throw StateError.NotParked()
        } catch (error: CoreError) {
            throw StateError.Core(error)
        }
        applyLoadedState(data, previous)
    }

    /**
     * Aplica [data] (sesión en pausa) y persiste la SRAM que trae; si no se puede, vuelve a [previous] con la semántica
     * transaccional de SPEC §5.4 (rollback, barrera y reparación). Común a [loadState] y [loadMoment].
     */
    private fun applyLoadedState(data: ByteArray, previous: com.joelbermudez.pocketgb.emulator.SavedState) {
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
        var first: java.util.concurrent.Future<Unit>? = null
        try {
            first = try { repairSubmit(coordinator) { target.persistLocal(sram) } } catch (_: IOException) { null }
            val body = repairBody(target, sram, first, done)
            repairThreadFactory(body, "pocketgb-save-repair").start()
        } catch (error: Throwable) {
            // A5V6-H3: sin hilo de reparación el hold extra no puede quedarse sin dueño. Nunca lanza: quien llamó
            // debe ver el RollbackFailed, no este fallo.
            Log.w(TAG, "No se pudo arrancar el hilo de reparación; se resuelve en línea o al cerrar la sesión", error)
            return repairWithoutThread(target, sram, first)
        }
        return try { done.await(repairWaitMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Cuerpo del hilo de reparación. Invariante (A5V6-H1): el hold solo se suelta tras una reparación CONFIRMADA;
     * cualquier `Throwable` (incluso dentro del propio manejador de reintentos) vuelve a reintentar con backoff.
     */
    private fun repairBody(
        target: SaveTarget,
        sram: ByteArray,
        first: java.util.concurrent.Future<Unit>?,
        done: java.util.concurrent.CountDownLatch,
    ) = Runnable {
        var future: java.util.concurrent.Future<Unit>? = first
        var delay = REPAIR_BACKOFF_START_MS
        // Una interrupción NO confirma que la reparación terminó ni que B dejó de escribir (A5V5-H1): se ignora,
        // se sigue esperando y la marca de interrupción se restaura al final.
        var interrupted = false
        var confirmed = false
        try {
            while (!confirmed) {
                try {
                    val pending = future
                    if (pending != null) {
                        try {
                            pending.get(REPAIR_POLL_MS, TimeUnit.MILLISECONDS)
                            confirmed = true
                        } catch (_: TimeoutException) {
                            // La tarea encolada pudo descartarse en un cierre forzado: no se espera para siempre.
                            if (coordinator.isFinished && !pending.isDone) future = null
                        } catch (error: java.util.concurrent.ExecutionException) {
                            throw error.cause ?: error
                        }
                    } else {
                        // Sin cola (cerrada): con el hilo de guardado ya salido, ningún B rezagado puede escribir.
                        coordinator.awaitThreadExit()
                        target.persistLocal(sram)
                        confirmed = true
                    }
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (error: Throwable) {
                    // Todo el manejador es a prueba de `Error` (A5V6-H1): lo que falle aquí solo provoca otra vuelta.
                    try { Log.w(TAG, "Reparación de la partida anterior fallida; se reintenta", error) } catch (_: Throwable) {}
                    val end = System.nanoTime() + delay * 1_000_000L
                    while (true) {
                        val left = (end - System.nanoTime()) / 1_000_000L
                        if (left <= 0) break
                        try { Thread.sleep(left) } catch (_: InterruptedException) { interrupted = true }
                    }
                    delay = minOf(delay * 2, REPAIR_BACKOFF_MAX_MS)
                    try {
                        future = try { repairSubmit(coordinator) { target.persistLocal(sram) } } catch (_: IOException) { null }
                    } catch (_: Throwable) {
                        // El reencolado falló (p. ej. OOM): se conserva el futuro fallido y se reintenta tras otra espera.
                    }
                }
            }
        } finally {
            // Solo se suelta la hold con la reparación confirmada; si el hilo muere sin ella, se conserva (la
            // huella queda bloqueada antes que arriesgar la partida).
            try { if (confirmed) dropLease() } finally {
                done.countDown()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    /** Reparación pendiente sin hilo propio: el hold lo resuelve [settleDeferredRepair] al cerrar de verdad. */
    private class DeferredRepair(val target: SaveTarget, val sram: ByteArray)

    private val deferredRepair = java.util.concurrent.atomic.AtomicReference<DeferredRepair?>(null)

    /** Fallback de [repairPrevious] cuando no hay hilo: intenta confirmar en [repairWaitMs]; si no, difiere al cierre. */
    private fun repairWithoutThread(target: SaveTarget, sram: ByteArray, first: java.util.concurrent.Future<Unit>?): Boolean {
        val confirmed = try {
            (first ?: repairSubmit(coordinator) { target.persistLocal(sram) }).get(repairWaitMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Throwable) {
            false
        }
        if (confirmed) dropLease() else deferredRepair.set(DeferredRepair(target, sram))
        return confirmed
    }

    /**
     * Llamado SOLO con el hilo de guardado ya salido (B no puede escribir): escribe A directamente y suelta el hold
     * extra de una reparación que no pudo tener hilo. Si falla, el hold se conserva.
     */
    private fun settleDeferredRepair() {
        val deferred = deferredRepair.getAndSet(null) ?: return
        try {
            deferred.target.persistLocal(deferred.sram)
            dropLease()
        } catch (error: Throwable) {
            Log.w(TAG, "No se pudo reponer la partida anterior al cerrar; la huella sigue bloqueada", error)
            deferredRepair.set(deferred)
        }
    }

    // ------------------------------------------------------------------ momentos (N6)

    private fun momentStore(): MomentStore = moments ?: throw StateError.Io(IOException("Sin almacén de momentos"))

    private inline fun <T> onSaveThread(crossinline block: () -> T): T = try {
        coordinator.runOnSaveThread { block() }
    } catch (error: IOException) {
        throw StateError.Io(error)
    } catch (error: TimeoutException) {
        throw StateError.Io(error)
    }

    /** Momentos y anillo «Antes de cargar». Bloquea: fuera del hilo principal. */
    fun moments(): MomentStore.Snapshot = onSaveThread { momentStore().snapshot() }

    fun momentThumbnail(kind: MomentStore.Kind, id: String): ByteArray? = onSaveThread { momentStore().thumbnail(kind, id) }

    /** La RAM del cartucho de ahora mismo (sin destino de guardado también), o `null` si el juego no tiene. */
    private fun currentSram(): ByteArray? = try {
        if (session.sramSaveSize > 0) session.copySram() else null
    } catch (_: CoreError) {
        null
    }

    /**
     * Crea un momento con la sesión en pausa: estado + RAM del cartucho del instante + miniatura + configuración (ND13).
     * No toca la partida ni el AUTO.
     */
    fun createMoment(name: String, playTimeMs: Long?): MomentStore.Moment {
        val saved = try {
            session.saveState()
        } catch (_: SessionError.NotParked) {
            throw StateError.NotParked()
        } catch (error: CoreError) {
            throw StateError.Core(error)
        }
        val sram = currentSram()
        return onSaveThread {
            momentStore().create(MomentStore.Capture(saved.bytes, sram, FramePng.encode(saved.pixels)), name, momentConfig, playTimeMs)
        }
    }

    /**
     * Carga un momento (o una entrada del anillo: «Recuperar») con la sesión en pausa (§3.3). Antes guarda la posición
     * actual en el anillo «Antes de cargar» (3 entradas, fuera de la rotación de backups); si eso falla, no se carga nada.
     * Después aplica el estado y persiste su SRAM como [loadState] (la partida anterior queda en el backup `.1`). El AUTO
     * NO se toca (unificado con iOS: cargar no lo pisa). La exclusión por huella la da la propia sesión, dueña del lease.
     */
    fun loadMoment(kind: MomentStore.Kind, id: String, label: String, playTimeMs: Long?) {
        if (session.state.value != SessionState.Paused) throw StateError.NotParked()
        val store = momentStore()
        val entry = onSaveThread { store.snapshot().find(kind, id) } ?: throw StateError.Io(IOException("El momento ya no existe"))
        if (!entry.hasState) throw StateError.NoState()
        val data = onSaveThread { store.loadState(kind, id) }
        val previous = try {
            session.saveState()
        } catch (_: SessionError.NotParked) {
            throw StateError.NotParked()
        } catch (error: CoreError) {
            throw StateError.Core(error)
        }
        val sram = currentSram()
        // N6A-H2: la entrada recuperada no sale del anillo; las expulsadas se borran solo si la carga se confirma.
        val pending = onSaveThread {
            store.pushBeforeLoadDeferred(
                MomentStore.Capture(previous.bytes, sram, FramePng.encode(previous.pixels)), label, momentConfig, playTimeMs,
                protect = id.takeIf { kind == MomentStore.Kind.BEFORE_LOAD },
            )
        }
        applyLoadedState(data, previous)
        try { onSaveThread { pending.commit() } } catch (_: Exception) {} // huérfanas: las retira recoverOrphans
    }

    fun deleteMoment(kind: MomentStore.Kind, id: String) = onSaveThread { momentStore().delete(kind, id) }

    fun updateMoment(id: String, name: String, tags: List<String>, collection: String?, note: String) =
        onSaveThread { momentStore().update(id, name, tags, collection, note) }

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
                // AUTO al salir: la continuación exacta (su fallo no impide salir). Nunca desde un núcleo dudoso (A9-H4).
                if (coreTrusted) try { saveState(StateSlot.AUTO) } catch (_: Exception) {}
            } else {
                saveRescueState() // J6: ranura de rescate aparte, que nunca pisa nada en silencio
            }
        }
        tryClose()
        return ExitResult.Clean
    }

    /**
     * Segundo plano (`ON_STOP`, A9; iOS `enterBackground`, D81-H4/D81V2-H3): con la sesión YA aparcada, vacía la SRAM y,
     * solo si quedó a salvo, guarda el estado AUTO, en ese orden (la fecha del estado queda posterior a la de la
     * partida). Así «Continuar» retoma la posición aunque el sistema mate la app sin pasar por Salir. No pausa ni
     * reanuda nada (eso lo hace el ciclo de vida en el hilo principal) y su fallo nunca toca la partida. Sin guardado
     * de confianza (persistencia desactivada tras un rollback fallido) no se escribe: el núcleo no es de fiar. Tampoco
     * mientras el AUTO sea el «deshacer» de un «Guardar actual y cargar» ([autoHoldsLoadUndo], A9-H3).
     * Bloquea: llamar fuera del hilo principal. @return `true` si se escribió el AUTO.
     */
    fun saveAutoStateIfParked(): Boolean {
        if (isClosed || session.state.value != SessionState.Paused) return false
        if (!coreTrusted) return false
        if (autoHoldsLoadUndo) return false // A9-H3: no pisar el «deshacer» de una carga hasta salir
        if (coordinator.hasTarget && !flushNow().isSafe) return false
        return try {
            saveState(StateSlot.AUTO)
            true
        } catch (_: Exception) {
            false // reanudada a la vez (no aparcada) o disco: la partida ya está a salvo igualmente
        }
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
                if (session.state.value == SessionState.Paused && coreTrusted) {
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
        // A5V6-H2: si el cierre del coordinador falla a medias NO se puede asumir que el hilo de guardado terminó:
        // se trata como "atascado" y el reaper espera su salida real (sin plazo) antes de cerrar y soltar el lease.
        val result = try {
            shutdownCoordinator(coordinator, closeGraceMs, closeKillWaitMs)
        } catch (error: Throwable) {
            Log.w(TAG, "El cierre del hilo de guardado falló; se espera a su salida real antes de liberar la huella", error)
            coordinator.forceStop()
            CloseResult.SaveThreadStuck(SaveCoordinator.THREAD_NAME)
        }
        closeResult = result
        when (result) {
            CloseResult.Closed -> {
                captureParkedFrame()
                try { settleDeferredRepair() } finally { try { session.close() } finally { dropLease() } }
            }
            is CloseResult.SaveThreadStuck -> {
                Log.w(TAG, "El hilo de guardado no terminó (${result.threadName}); el handle nativo se libera cuando salga")
                Thread({
                    // La propiedad de la huella NUNCA se suelta mientras el hilo de guardado siga vivo: una
                    // interrupción no es una confirmación de que la escritura antigua terminó (A5V4-H1). Se sigue
                    // esperando y se restaura la marca de interrupción al final.
                    var interrupted = false
                    try {
                        // A5V7-H1: el cierre pudo fallar antes de llegar al ejecutor; se vuelve a pedir sin lanzar.
                        coordinator.forceStop()
                        while (true) {
                            try {
                                coordinator.awaitThreadExit()
                                break
                            } catch (_: InterruptedException) {
                                interrupted = true
                            }
                        }
                        try { settleDeferredRepair() } finally { try { session.close() } finally { dropLease() } }
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt()
                    }
                }, "pocketgb-save-reaper").apply { isDaemon = true }.start()
            }
        }
        return result
    }

    /** N5: el fotograma actual si la sesión está en pausa («Usar como portada»); `null` si no. Nunca lanza. */
    fun pausedFrame(): IntArray? = runCatching {
        if (session.state.value == SessionState.Paused) session.copyFrame() else null
    }.getOrNull()

    /** K9: entrega el último fotograma a [parkedFrameCallback]. Envuelto en `runCatching`: nunca cambia el cierre. */
    private fun captureParkedFrame() {
        val callback = parkedFrameCallback ?: return
        runCatching {
            if (session.state.value == SessionState.Paused) callback(session.copyFrame())
        }
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

        /** Desplazamiento de la secuencia de guardado cuando cambia el tamaño del `.sav` (EEPROM 512 B → 8 KiB, N8). */
        const val SIZE_CHANGE_SEQ = 1L shl 40
    }
}
