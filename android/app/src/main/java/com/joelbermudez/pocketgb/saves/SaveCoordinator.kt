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
    @Volatile private var closed = false
    private var failed: Throwable? = null

    /** Duraciones (ns) de los [flushSync] medidos, para p50/p99 (solo se leen tras la sesión o en tests). */
    private val durations = ArrayList<Long>()

    val hasTarget: Boolean get() = target != null

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
        if (closed || target == null) return
        val seq = try { source.dirtySeq() } catch (_: Throwable) { return }
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
    fun flushSync(): FlushResult {
        if (target == null) return FlushResult.Unchanged
        val started = System.nanoTime()
        val future = try {
            executor.submit(Callable { flushLocked(sync = true) })
        } catch (_: RejectedExecutionException) {
            return FlushResult.Failed(IllegalStateException("El guardado ya está cerrado"))
        }
        val result = try {
            future.get(flushTimeoutMs, TimeUnit.MILLISECONDS)
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
            setFailed(TimeoutException("El guardado no terminó en ${flushTimeoutMs / 1000} s"))
        }
        return result
    }

    /** Siempre en el hilo de guardado. */
    private fun flushLocked(sync: Boolean): FlushResult {
        val target = target ?: return FlushResult.Unchanged
        val data = try {
            source.copy()
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
        target.whenMirrorIdle {
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
     * Para el hilo y lo une (I3): debe llamarse ANTES de liberar el handle nativo. Las tareas en cola que
     * no han empezado se descartan; la que está en vuelo termina.
     */
    override fun close() {
        closed = true
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
        // `awaitTermination` vuelve cuando el ejecutor termina, no cuando el hilo ha salido de `run()`: se une
        // el propio hilo para que, al volver, ya no exista y nadie pueda tocar el handle (I3).
        saveThread?.takeIf { it !== Thread.currentThread() }?.join(5_000)
    }

    companion object {
        const val THREAD_NAME = "pocketgb-saves"
    }
}
