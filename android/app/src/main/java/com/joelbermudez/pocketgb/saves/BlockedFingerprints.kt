package com.joelbermudez.pocketgb.saves

/**
 * Huellas cuya partida tiene un guardado pendiente en manos de la app (sesión huérfana que sigue reintentando,
 * o reparación de la partida anterior tras un rollback fallido). Mientras una huella está aquí NADIE más puede
 * abrirla ni restaurar sus copias: una escritura tardía de la sesión pendiente pisaría esa restauración o a la
 * sesión nueva (A5 2ª vuelta, A5V2-H1/H3). Es un contador: cada dueño hace [acquire] y [release] una vez.
 */
class BlockedFingerprints {
    private val counts = HashMap<String, Int>()

    @Synchronized fun acquire(fingerprint: String) {
        counts[fingerprint] = (counts[fingerprint] ?: 0) + 1
    }

    @Synchronized fun release(fingerprint: String) {
        val left = (counts[fingerprint] ?: return) - 1
        if (left <= 0) counts.remove(fingerprint) else counts[fingerprint] = left
    }

    @Synchronized fun isBlocked(fingerprint: String): Boolean = (counts[fingerprint] ?: 0) > 0

    companion object {
        /** Registro de la app (el que usan el lanzador, Ajustes › Partidas y las sesiones reales). */
        val shared = BlockedFingerprints()
    }
}

/** La partida de esa huella tiene un guardado pendiente: no se puede abrir ni restaurar todavía. */
class SavePendingException(val fingerprint: String) :
    IllegalStateException("Guardado pendiente de esa partida: espera a que termine e inténtalo de nuevo.")
