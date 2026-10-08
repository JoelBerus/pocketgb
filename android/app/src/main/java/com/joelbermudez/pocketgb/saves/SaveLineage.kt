package com.joelbermudez.pocketgb.saves

import java.util.Locale

/**
 * N7a · linaje de la partida (N-README §3.4). Decisión pura, sin E/S ni fechas: se basa solo en huellas (SHA-256 del
 * contenido) y en el historial acotado de escrituras propias del espejo ([SaveStore]). Así un reloj desfasado entre
 * equipos no puede hacer ganar a una partida vieja (la regla por fecha solo queda para la primera vez, sin historial).
 */
object SaveLineage {
    /** Qué hacer al abrir, según la tabla de §3.4. */
    enum class Mirror {
        /** Espejo = local: nada. */
        SAME,

        /** Espejo = una escritura nuestra anterior (terminó tarde o es antigua): gana la local y se reescribe el espejo. */
        OWN_OLDER,

        /** Espejo desconocido y la local no cambió desde nuestra última escritura: cambio externo, se instala con backup y aviso. */
        EXTERNAL_CHANGE,

        /** Espejo desconocido y la local sí cambió: divergencia (se pregunta; la otra queda como momento «Conflicto …» y en backup). */
        DIVERGENCE,

        /** Sin historial (primera vez): la regla de siempre por fecha, con backup. */
        NO_HISTORY,

        /** Espejo borrado (o sin partida local): se recrea desde la local / se importa como hasta ahora. */
        MIRROR_MISSING,
    }

    /**
     * @param ownHashes huellas de nuestras escrituras del espejo (confirmadas y write-ahead), las más recientes primero.
     * @param lastOwn huellas de nuestra ÚLTIMA escritura (la última confirmada y la última intentada): si la local es una
     *   de ellas, la local no cambió desde entonces.
     */
    fun classifyMirror(localHash: String?, mirrorHash: String?, ownHashes: Collection<String>, lastOwn: Collection<String>): Mirror {
        if (mirrorHash == null || localHash == null) return Mirror.MIRROR_MISSING
        if (mirrorHash == localHash) return Mirror.SAME
        if (ownHashes.isEmpty()) return Mirror.NO_HISTORY
        if (mirrorHash in ownHashes) return Mirror.OWN_OLDER
        return if (localHash in lastOwn) Mirror.EXTERNAL_CHANGE else Mirror.DIVERGENCE
    }

    /** Resultado de aplicar el linaje a una partida que llega en un paquete (`.pgbm`, N7b) o un `.sav` importado. */
    enum class Incoming {
        /** No hay partida local: se instala. */
        INSTALL,

        /** La que llega es la que ya hay: nada que instalar. */
        ALREADY_CURRENT,

        /** La que llega ya la tuvimos (es anterior a la local): no se instala; queda en backup. */
        STALE,

        /** La que llega parte de la local (`base` = local): avance, se instala con backup. */
        ADVANCE,

        /** Las dos cambiaron por separado (o no se puede probar el avance): se pregunta. */
        DIVERGENCE,
    }

    /**
     * @param incomingHash `sav_sha256` del paquete (ya comprobado contra los bytes).
     * @param incomingBase `base_sav_sha256` (null = desconocida: un `.sav` crudo nunca la trae).
     * @param knownHashes huellas que este equipo ya tuvo (escrituras propias del espejo y partidas recibidas).
     *
     * Latencia: si el paquete anuncia una base que aquí aún no llegó (la local no es esa base), no se puede probar el
     * avance y se trata como divergencia: se pregunta, nunca se pisa.
     */
    fun classifyIncoming(localHash: String?, incomingHash: String, incomingBase: String?, knownHashes: Collection<String>): Incoming {
        val inc = incomingHash.lowercase(Locale.ROOT)
        val base = incomingBase?.lowercase(Locale.ROOT)
        if (localHash == null) return Incoming.INSTALL
        if (inc == localHash) return Incoming.ALREADY_CURRENT
        if (base != null && base == localHash) return Incoming.ADVANCE
        if (inc in knownHashes) return Incoming.STALE
        return Incoming.DIVERGENCE
    }

    // MARK: copias en conflicto del proveedor

    private val ORDINAL = Regex("""^(.+?)(?: \d+| \(\d+\))$""")

    /**
     * ¿Es [name] una copia en conflicto que el proveedor creó para el espejo `<base>.sav`? Formas conocidas:
     * `X 2.sav` (iCloud / Drive), `X (1).sav` (Drive, OneDrive), `X.sync-conflict-20261008-…sav` (Syncthing) y
     * `X (conflicted copy …).sav` / `X (… conflicted copy …).sav` (Dropbox). Sin distinguir mayúsculas. El propio
     * `<base>.sav` no lo es. Se listan en Ajustes › Partidas; **nunca** se borran.
     */
    fun isProviderConflictCopy(base: String, name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        val b = base.lowercase(Locale.ROOT)
        if (!lower.endsWith(".sav") || lower == "$b.sav") return false
        val stem = lower.removeSuffix(".sav")
        if (stem.startsWith("$b.sync-conflict-")) return true
        if (stem.startsWith("$b ") && "conflicted copy" in stem) return true
        val m = ORDINAL.matchEntire(stem) ?: return false
        return m.groupValues[1] == b
    }
}
