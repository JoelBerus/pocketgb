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
    class Request(val data: ByteArray, val store: SaveStore, val writer: (ByteArray) -> Long?)

    // Un solo hilo daemon por huella; se libera tras 5 s de inactividad.
    private val executor = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "pocketgb-mirror-$fingerprint").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private val lock = Any()
    private var pendingRequest: Request? = null
    /** Contenido que se está escribiendo ahora (fuera del lock). */
    private var inFlight: ByteArray? = null
    private var workerRunning = false
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
            start = !workerRunning
            if (start) workerRunning = true
        }
        if (start) executor.execute(::drain)
    }

    fun retryIfNeeded(request: Request) {
        val start: Boolean
        synchronized(lock) {
            if (!needsRetry || (pendingRequest == null && inFlight?.contentEquals(request.data) == true)) return
            pendingRequest = request
            start = !workerRunning
            if (start) workerRunning = true
        }
        if (start) executor.execute(::drain)
    }

    fun whenIdle(callback: () -> Unit) {
        synchronized(lock) {
            if (workerRunning) {
                idleCallbacks += callback
                return
            }
        }
        callback()
    }

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
                request.store.recordSuccessfulMirror(request.data, observedDate)
                synchronized(lock) { inFlight = null }
            } catch (_: Exception) {
                val callbacks: List<() -> Unit>
                val newerRequestExists: Boolean
                synchronized(lock) {
                    inFlight = null
                    newerRequestExists = pendingRequest != null
                    if (!newerRequestExists) pendingRequest = request
                    needsRetry = true
                    if (!newerRequestExists) workerRunning = false
                    callbacks = if (newerRequestExists) emptyList() else idleCallbacks.toList()
                    if (!newerRequestExists) idleCallbacks.clear()
                }
                runAll(callbacks)
                if (!newerRequestExists) return
            }
        }
    }
}
