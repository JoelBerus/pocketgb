package com.joelbermudez.pocketgb.saves

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Un canal (hilo serie) de escritura del espejo por huella, a nivel de app: sobrevive a las sesiones. */
class MirrorChannelRegistry {
    private val channels = HashMap<String, MirrorChannel>()

    @Synchronized
    fun channel(fingerprint: String): MirrorChannel = channels.getOrPut(fingerprint) { MirrorChannel(fingerprint) }

    companion object {
        val shared = MirrorChannelRegistry()
    }
}

/**
 * Cola serie del espejo de una huella con coalescencia: una escritura que ya empezó no se puede cancelar,
 * pero mientras está bloqueada solo se conserva el contenido más reciente recibido. Un fallo deja el
 * contenido marcado para reintento y libera a quien espera. Nunca toca la copia local.
 * Portado de `MirrorChannel` de iOS.
 */
class MirrorChannel internal constructor(fingerprint: String) {
    class Request(val data: ByteArray, val store: SaveStore, val location: String? = null, val writer: (ByteArray) -> Long?)

    // Un solo hilo daemon por huella; se libera tras 5 s de inactividad.
    private val executor = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "pocketgb-mirror-$fingerprint").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private val lock = Any()
    private var pendingRequest: Request? = null
    /** Contenido que se está escribiendo ahora (fuera del lock). */
    private var inFlight: ByteArray? = null
    private var workerRunning = false
    /** Lease de lectura ([withIdleLease]): mientras es `true` ningún escritor puede empezar. */
    private var leased = false
    private var needsRetry = false
    private val idleCallbacks = ArrayList<() -> Unit>()

    val pending: Boolean get() = synchronized(lock) { needsRetry || workerRunning || pendingRequest != null }

    fun markNeedsRetry() = synchronized(lock) { needsRetry = true }

    fun enqueue(request: Request) {
        val start: Boolean
        synchronized(lock) {
            // El mismo contenido que ya se está escribiendo no se repite: si esa escritura falla, ella misma
            // se reencola (ver `drain`).
            if (pendingRequest == null && inFlight?.contentEquals(request.data) == true) return
            pendingRequest = request
            needsRetry = true
            start = !workerRunning && !leased
            if (start) workerRunning = true
        }
        if (start) executor.execute(::drain)
    }

    fun retryIfNeeded(request: Request) {
        val start: Boolean
        synchronized(lock) {
            if (!needsRetry || (pendingRequest == null && inFlight?.contentEquals(request.data) == true)) return
            pendingRequest = request
            start = !workerRunning && !leased
            if (start) workerRunning = true
        }
        if (start) executor.execute(::drain)
    }

    fun whenIdle(callback: () -> Unit) {
        synchronized(lock) {
            if (workerRunning || leased) {
                idleCallbacks += callback
                return
            }
        }
        callback()
    }

    /**
     * Espera (como mucho [timeoutMs]) a que no haya escritura en vuelo. `false` si sigue ocupado. El callback
     * que deja registrado se descarta al vencer el plazo (no se acumulan esperas muertas).
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val callback: () -> Unit = { latch.countDown() }
        whenIdle(callback)
        val idle = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!idle) synchronized(lock) { idleCallbacks.remove(callback) }
        return idle
    }

    /**
     * Ejecuta [block] (una lectura del espejo) con el canal en reposo y SIN que ningún escritor pueda empezar hasta
     * que termine: espera como mucho [timeoutMs] a que no haya escritura en vuelo y, en el mismo instante y bajo
     * el mismo lock, toma el lease. Las escrituras que lleguen durante [block] se conservan (coalescidas) y
     * arrancan al soltarlo. `null` si el canal no quedó en reposo a tiempo (no se llama a [block]).
     */
    fun <T> withIdleLease(timeoutMs: Long, block: () -> T): T? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val callback: () -> Unit = { latch.countDown() }
            val acquired = synchronized(lock) {
                if (!workerRunning && !leased) {
                    leased = true
                    true
                } else {
                    idleCallbacks += callback
                    false
                }
            }
            if (acquired) break
            val remainingNs = deadline - System.nanoTime()
            val woke = try {
                remainingNs > 0 && latch.await(remainingNs, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!woke) {
                synchronized(lock) { idleCallbacks.remove(callback) }
                return null
            }
        }
        try {
            return block()
        } finally {
            releaseLease()
        }
    }

    private fun releaseLease() {
        val start: Boolean
        var callbacks: List<() -> Unit> = emptyList()
        synchronized(lock) {
            leased = false
            start = pendingRequest != null && !workerRunning
            if (start) workerRunning = true
            // Quien esperaba un hueco (otro lease o `whenIdle`) se despierta si no empieza un escritor ahora.
            if (!start && !workerRunning) {
                callbacks = idleCallbacks.toList()
                idleCallbacks.clear()
            }
        }
        if (start) executor.execute(::drain)
        runAll(callbacks)
    }

    /** Callbacks en espera (tests y diagnóstico). */
    internal val idleCallbackCount: Int get() = synchronized(lock) { idleCallbacks.size }

    private fun runAll(callbacks: List<() -> Unit>) {
        for (cb in callbacks) try { cb() } catch (_: Exception) {}
    }

    private fun drain() {
        while (true) {
            var next: Request? = null
            var callbacks: List<() -> Unit> = emptyList()
            synchronized(lock) {
                val p = pendingRequest
                if (p == null) {
                    needsRetry = false
                    workerRunning = false
                    callbacks = idleCallbacks.toList()
                    idleCallbacks.clear()
                } else {
                    pendingRequest = null
                    inFlight = p.data
                    next = p
                }
            }
            val request = next
            if (request == null) {
                runAll(callbacks) // fuera del lock
                return
            }
            try {
                // Write-ahead local: si el proceso muere tras el replace remoto y antes de confirmarlo, la
                // próxima apertura aún reconoce el contenido como propio.
                request.store.recordMirrorAttempt(request.data)
                val observedDate = request.writer(request.data)
                request.store.recordSuccessfulMirror(request.data, observedDate, request.location)
                synchronized(lock) { inFlight = null }
            } catch (error: Throwable) {
                // Throwable, no solo Exception: un Error (p. ej. OutOfMemoryError o un fallo del proveedor) no puede
                // dejar `workerRunning = true` para siempre ni a nadie esperando en `whenIdle`.
                val callbacks: List<() -> Unit>
                // Con un Error el hilo no sigue: se libera el canal aunque haya una petición más nueva (queda
                // pendiente y la siguiente `enqueue`/`retryIfNeeded` arrancará otro trabajador).
                val fatal = error !is Exception
                val newerRequestExists: Boolean
                synchronized(lock) {
                    inFlight = null
                    newerRequestExists = pendingRequest != null
                    if (!newerRequestExists) pendingRequest = request
                    needsRetry = true
                    val stop = fatal || !newerRequestExists
                    if (stop) workerRunning = false
                    callbacks = if (stop) idleCallbacks.toList() else emptyList()
                    if (stop) idleCallbacks.clear()
                }
                runAll(callbacks)
                if (fatal) throw error
                if (!newerRequestExists) return
            }
        }
    }
}
