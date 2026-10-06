package com.joelbermudez.pocketgb.saves

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Resultado de un vaciado síncrono de la SRAM a la copia local. */
sealed interface FlushResult {
    /** Se escribió una versión nueva y quedó confirmada en disco. */
    data object Saved : FlushResult

    /** Lo que hay en disco ya coincidía con el núcleo: no hizo falta escribir. */
    data object Unchanged : FlushResult

    /** No se pudo copiar o escribir; la copia local NO está al día. */
    class Failed(val error: Throwable) : FlushResult {
        override fun toString() = "Failed(${error.message})"
    }

    /** El hilo de guardado no respondió en el plazo: el guardado queda pendiente (J5), no confirmado. */
    data object TimedOut : FlushResult
}

/** Resultado de cerrar el guardado de una sesión (I3). */
sealed interface CloseResult {
    /** El hilo de guardado terminó de verdad: ya no puede tocar nada y el handle nativo se puede liberar. */
    data object Closed : CloseResult

    /**
     * El hilo de guardado SIGUE VIVO (una operación de archivo o del proveedor que no responde): el handle
     * nativo NO debe liberarse todavía. Quien posee el handle espera a [SaveCoordinator.awaitThreadExit].
     */
    class SaveThreadStuck(val threadName: String) : CloseResult {
        override fun toString() = "SaveThreadStuck($threadName)"
    }
}

/** El guardado ya está cerrado: ninguna copia nativa nueva puede empezar (I3). */
class SaveClosedException : IllegalStateException("El guardado ya está cerrado")

val FlushResult.isSafe: Boolean get() = this == FlushResult.Saved || this == FlushResult.Unchanged

/**
 * Persistencia de la SRAM de una sesión (SPEC §5.2), en un único hilo `pocketgb-saves` por sesión.
 *
 * **I4**: copiar la SRAM y escribirla ocurren SIEMPRE en este hilo y en ese orden, tanto desde el ciclo
 * periódico como desde [flushSync]; así una copia vieja nunca pisa a otra más nueva. Con un solo hilo la
 * cola es FIFO: un vaciado síncrono encolado detrás de una escritura en vuelo la espera y después copia
 * lo último del núcleo (por eso, tras cargar un estado, el disco acaba igual que el núcleo).
 *
 * - `lastQueued`: lo último que se intentó escribir (evita reescribir lo mismo cada tick).
 * - `confirmed`: lo último que se sabe que está en disco. Un vaciado síncrono compara con `confirmed`:
 *   así una escritura periódica que falló justo antes se reintenta aquí y no se pierde.
 * - Si una escritura falla: `lastQueued = null`, se avisa ([onStatus] con el error) y el siguiente ciclo
 *   (un debounce después) la reintenta.
 *
 * @param baseline contenido que ya está en disco al abrir (para no reescribir lo que no cambió).
 * @param target destino; `null` = sesión que no guarda (tamaño incorrecto, J10): solo sirve el hilo
 *   para las operaciones de estados.
 * @param autoTick con `false` no se programa el ciclo (los tests lo disparan con [tickNow]).
 * @param onStatus `null` = la copia local está al día; con error, hay un guardado pendiente o fallido.
 * - **I3 (cierre)**: [shutdown] marca la cola cerrada BAJO la misma compuerta que protege cada copia nativa
 *   (`source.copy()`/`dirtySeq()`): tras volver, ninguna copia nueva puede empezar aunque el hilo siga ocupado
 *   en un archivo que no responde. Solo si el hilo terminó de verdad devuelve [CloseResult.Closed].
 *
 * @param onMirrorTrouble el espejo quedó pendiente tras un intento (aviso no bloqueante, una vez por racha).
 */
class SaveCoordinator(
    private val source: SramSource,
    private val target: SaveTarget?,
    baseline: ByteArray? = null,
    private val policy: SramFlushPolicy = SramFlushPolicy({ System.nanoTime() / 1_000_000 }),
    private val tickMs: Long = 100,
    private val flushTimeoutMs: Long = 3_000,
    autoTick: Boolean = true,
    private val onStatus: (Throwable?) -> Unit = {},
    private val onMirrorTrouble: () -> Unit = {},
) : AutoCloseable {
    @Volatile private var saveThread: Thread? = null

    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, THREAD_NAME).apply { isDaemon = true }.also { saveThread = it }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    private val lock = Any()
    private var confirmed: ByteArray? = baseline
    private var lastQueued: ByteArray? = baseline
    private var lastDirtySeq: Long = if (target != null) safeDirtySeq() else 0L
    @Volatile private var forceFlush = false
    @Volatile private var mirrorTroubleReported = false
    /** Compuerta de las llamadas a [SramSource]: `closed` solo cambia (y se lee antes de copiar) bajo ella. */
    private val sourceGate = Any()
    @Volatile private var closed = false
    @Volatile private var persistenceEnabled = true
    private val mirrorWatchPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var failed: Throwable? = null

    /** Duraciones (ns) de los [flushSync] medidos, para p50/p99 (solo se leen tras la sesión o en tests). */
    private val durations = ArrayList<Long>()

    /** Hay destino y la persistencia no se ha desactivado ([disablePersistence]). */
    val hasTarget: Boolean get() = target != null && persistenceEnabled

    /** El hilo de guardado existe y sigue vivo. */
    val isSaveThreadAlive: Boolean get() = saveThread?.isAlive == true

    private fun <T> withSource(block: () -> T): T = synchronized(sourceGate) {
        if (closed) throw SaveClosedException()
        block()
    }

    /**
     * Deja de persistir para siempre (el núcleo está en un estado que no se debe escribir: el rollback de un
     * estado cargado falló). No toca el disco; el error queda como problema de guardado visible.
     */
    fun disablePersistence(reason: Throwable) {
        persistenceEnabled = false
        setFailed(reason)
    }

    /** Último error de guardado sin resolver, o `null`. Seguro desde cualquier hilo. */
    val pendingError: Throwable? get() = synchronized(lock) { failed }

    /** Cuánto ha tardado cada vaciado síncrono (nanosegundos), en orden. */
    fun flushDurationsNanos(): List<Long> = synchronized(durations) { durations.toList() }

    init {
        if (autoTick && target != null) {
            executor.scheduleWithFixedDelay(::safeTick, tickMs, tickMs, TimeUnit.MILLISECONDS)
        }
    }

    // ------------------------------------------------------------------ ciclo periódico

    private fun safeTick() {
        try {
            tick()
        } catch (_: Throwable) {
            // Un ScheduledExecutor cancela el ciclo si una tarea lanza: nada puede escapar de aquí.
        }
    }

    private fun tick() {
        if (closed || target == null || !persistenceEnabled) return
        val seq = try { withSource { source.dirtySeq() } } catch (_: Throwable) { return }
        val dirty = seq != lastDirtySeq || forceFlush
        lastDirtySeq = seq
        forceFlush = false
        if (policy.poll(dirty)) {
            flushLocked(sync = false)
        }
    }

    /** Ejecuta un ciclo ahora y espera a que acabe (tests). */
    internal fun tickNow() {
        executor.submit(::tick).get(10, TimeUnit.SECONDS)
    }

    /** Pide que el próximo ciclo vacíe aunque el juego no haya guardado (p. ej. tras un rollback de estado). */
    fun requestFlush() {
        forceFlush = true
    }

    // ------------------------------------------------------------------ vaciado síncrono

    /**
     * Copia la SRAM y la deja en disco (o confirma que ya estaba), esperando como mucho [flushTimeoutMs].
     * Se encola en el MISMO hilo que las escrituras periódicas (I4). Un plazo agotado no cancela nada: la
     * tarea sigue en cola y el siguiente vaciado la sucede; el llamador debe tratarlo como "pendiente".
     */
    fun flushSync(timeoutMs: Long = flushTimeoutMs): FlushResult {
        if (target == null || !persistenceEnabled) return FlushResult.Unchanged
        val started = System.nanoTime()
        val future = try {
            executor.submit(Callable { flushLocked(sync = true) })
        } catch (_: RejectedExecutionException) {
            return FlushResult.Failed(IllegalStateException("El guardado ya está cerrado"))
        }
        val result = try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            FlushResult.TimedOut
        } catch (error: ExecutionException) {
            FlushResult.Failed(error.cause ?: error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            FlushResult.Failed(error)
        }
        synchronized(durations) { durations += System.nanoTime() - started }
        if (result == FlushResult.TimedOut) {
            setFailed(TimeoutException("El guardado no terminó en ${timeoutMs / 1000.0} s"))
        }
        return result
    }

    /** Siempre en el hilo de guardado. */
    private fun flushLocked(sync: Boolean): FlushResult {
        val target = target ?: return FlushResult.Unchanged
        if (!persistenceEnabled) return FlushResult.Unchanged
        val data = try {
            withSource { source.copy() }
        } catch (error: SaveClosedException) {
            return FlushResult.Failed(error) // cerrado: no es un fallo de disco y no se reintenta
        } catch (error: Throwable) {
            policy.onFlushFailed()
            setFailed(error)
            return FlushResult.Failed(error)
        }
        val reference = synchronized(lock) { if (sync) confirmed else lastQueued }
        if (reference != null && reference.contentEquals(data)) {
            if (sync) {
                // Ya está en disco; aprovecha para reintentar un espejo pendiente.
                try { target.retryMirrorIfNeeded(data) } catch (_: Throwable) {}
                policy.onFlushDone()
                clearFailed()
            }
            return FlushResult.Unchanged
        }
        if (!persistenceEnabled) return FlushResult.Unchanged
        synchronized(lock) { lastQueued = data }
        return try {
            target.persistLocal(data)
            synchronized(lock) { confirmed = data }
            if (sync) policy.onFlushDone()
            clearFailed()
            watchMirror(target)
            FlushResult.Saved
        } catch (error: Throwable) {
            synchronized(lock) { lastQueued = null }
            policy.onFlushFailed()
            setFailed(error)
            FlushResult.Failed(error)
        }
    }

    private fun watchMirror(target: SaveTarget) {
        // Una sola espera a la vez: con un proveedor bloqueado no se apilan callbacks en el canal (A5 Opus H12).
        if (!mirrorWatchPending.compareAndSet(false, true)) return
        target.whenMirrorIdle {
            mirrorWatchPending.set(false)
            val pending = target.mirrorPending
            if (pending && !mirrorTroubleReported) {
                mirrorTroubleReported = true
                try { onMirrorTrouble() } catch (_: Throwable) {}
            } else if (!pending) {
                mirrorTroubleReported = false
            }
        }
    }

    private fun setFailed(error: Throwable) {
        val changed = synchronized(lock) {
            val first = failed == null
            failed = error
            first
        }
        if (changed) try { onStatus(error) } catch (_: Throwable) {}
    }

    private fun clearFailed() {
        val changed = synchronized(lock) {
            val had = failed != null
            failed = null
            had
        }
        if (changed) try { onStatus(null) } catch (_: Throwable) {}
    }

    private fun safeDirtySeq(): Long = try { source.dirtySeq() } catch (_: Throwable) { 0L }

    // ------------------------------------------------------------------ otras operaciones en el hilo de guardado

    /**
     * Ejecuta [block] en el hilo de guardado y espera su resultado (PNG y escritura atómica de un estado,
     * lectura de un estado). Relanza la excepción de [block]; [TimeoutException] si el hilo no responde.
     */
    fun <T> runOnSaveThread(timeoutMs: Long = 5_000, block: () -> T): T {
        val future = try {
            executor.submit(Callable { block() })
        } catch (_: RejectedExecutionException) {
            throw java.io.IOException("El guardado ya está cerrado")
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    /**
     * Encola [block] en el hilo de guardado SIN esperar (FIFO: corre cuando termine lo que ya estaba en vuelo) y
     * devuelve su futuro. Lanza [java.io.IOException] si el guardado ya está cerrado.
     */
    fun <T> submitOnSaveThread(block: () -> T): java.util.concurrent.Future<T> = try {
        executor.submit(Callable { block() })
    } catch (_: RejectedExecutionException) {
        throw java.io.IOException("El guardado ya está cerrado")
    }

    /** Cerrado y con el hilo de guardado ya terminado: lo encolado y no ejecutado no se ejecutará nunca. */
    val isFinished: Boolean get() = closed && saveThread?.isAlive != true

    /**
     * Cierra el guardado (I3) y comprueba que el hilo terminó de verdad. Orden: 1) marca cerrado bajo la
     * compuerta (ninguna copia nativa nueva); 2) deja que lo ya encolado termine (las tareas nuevas ven el
     * cierre y salen sin tocar nada); 3) pasado [graceMs], interrumpe; 4) une el hilo [killWaitMs]. Si sigue
     * vivo devuelve [CloseResult.SaveThreadStuck] y quien posee el handle NO debe liberarlo.
     */
    fun shutdown(graceMs: Long = 10_000, killWaitMs: Long = 5_000): CloseResult {
        synchronized(sourceGate) { closed = true }
        executor.shutdown()
        if (!awaitQuietly(graceMs)) {
            executor.shutdownNow()
            awaitQuietly(killWaitMs)
        }
        // `awaitTermination` vuelve cuando el ejecutor termina, no cuando el hilo ha salido de `run()`: se une
        // el propio hilo para que, al volver, ya no exista.
        val thread = saveThread?.takeIf { it !== Thread.currentThread() }
        if (thread != null) {
            try { thread.join(killWaitMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        return if (thread != null && thread.isAlive) CloseResult.SaveThreadStuck(thread.name) else CloseResult.Closed
    }

    private fun awaitQuietly(ms: Long): Boolean = try {
        executor.awaitTermination(ms, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** Bloquea hasta que el hilo de guardado haya salido (sin plazo): lo usa el "reaper" que libera el handle tarde. */
    fun awaitThreadExit() {
        val thread = saveThread?.takeIf { it !== Thread.currentThread() } ?: return
        thread.join()
    }

    /** Equivale a [shutdown] con los plazos por defecto; el resultado se ignora (usar [shutdown] para saberlo). */
    override fun close() {
        shutdown()
    }

    companion object {
        const val THREAD_NAME = "pocketgb-saves"
    }
}
