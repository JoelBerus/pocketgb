package com.joelbermudez.pocketgb.saves

import java.util.concurrent.atomic.AtomicBoolean

/**
 * PROPIEDAD EXCLUSIVA ATÓMICA por huella de cartucho (A5 3ª vuelta, A5V3-H1). Sustituye al antiguo "comprobar si está
 * bloqueada y luego actuar", que dejaba una ventana entre la comprobación y la acción.
 *
 * Invariante: como máximo UN [Lease] vivo por huella. Quien quiera tocar los archivos de una partida (abrir el juego,
 * reparar la partida anterior, restaurar una copia) debe antes conseguir el lease con [tryAcquire]; esa operación
 * comprueba y adquiere bajo el mismo monitor, así que no hay ventana en la que otro la coja entre medias.
 *
 * - **Sesión**: [com.joelbermudez.pocketgb.game.GameLauncher] adquiere el lease ANTES de tocar ningún archivo de
 *   `saves/` y se lo entrega a la `GameSession`, que lo conserva durante TODA su vida (también si pasa a huérfana:
 *   es el mismo token, no se libera ni se vuelve a adquirir) y lo suelta al cerrarse de verdad (hilo de guardado ya
 *   terminado) y cuando no queda ninguna reparación en curso.
 * - **Restaurar**: [withExclusive] envuelve la comprobación Y la mutación, y siempre libera en `finally`.
 * - Por tanto, dos actividades o ViewModels no pueden abrir la misma huella a la vez, y una escritura tardía de una
 *   sesión antigua nunca coincide con una restauración o con una sesión nueva.
 *
 * Ningún camino debe dejar un lease sin liberar: [Lease.close] es idempotente y todos los usuarios lo llaman en
 * `finally`.
 */
class FingerprintOwnership {
    private val owners = HashMap<String, Lease>()

    /** Concesión exclusiva de una huella. Se libera con [close] (idempotente). */
    inner class Lease internal constructor(val fingerprint: String, val owner: String) : AutoCloseable {
        private val released = AtomicBoolean(false)

        val isReleased: Boolean get() = released.get()

        override fun close() {
            if (released.compareAndSet(false, true)) release(this)
        }
    }

    /** Adquiere la huella de forma atómica, o `null` si ya tiene dueño (sesión, huérfana, reparación o restauración). */
    @Synchronized fun tryAcquire(fingerprint: String, owner: String): Lease? {
        if (owners.containsKey(fingerprint)) return null
        return Lease(fingerprint, owner).also { owners[fingerprint] = it }
    }

    @Synchronized private fun release(lease: Lease) {
        if (owners[lease.fingerprint] === lease) owners.remove(lease.fingerprint)
    }

    @Synchronized fun isOwned(fingerprint: String): Boolean = owners.containsKey(fingerprint)

    @Synchronized fun ownerOf(fingerprint: String): String? = owners[fingerprint]?.owner

    /** Permiso exclusivo corto: [block] corre con el lease y este se libera siempre. */
    fun <T> withExclusive(fingerprint: String, owner: String, block: () -> T): T {
        val lease = tryAcquire(fingerprint, owner) ?: throw SavePendingException(fingerprint)
        try {
            return block()
        } finally {
            lease.close()
        }
    }

    companion object {
        /** Registro de la app (el que usan el lanzador, Ajustes › Partidas y las sesiones reales). */
        val shared = FingerprintOwnership()
    }
}

/** La partida de esa huella ya tiene dueño (sesión abierta, guardado pendiente o restauración): no se puede tocar aún. */
class SavePendingException(val fingerprint: String) :
    IllegalStateException("Guardado pendiente de esa partida: espera a que termine e inténtalo de nuevo.")
