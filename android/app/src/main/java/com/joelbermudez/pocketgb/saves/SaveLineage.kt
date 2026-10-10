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
     * @param ownHashes historial propio: escrituras del espejo (confirmadas y write-ahead) y, ND20 (b′) como iOS, partidas
     *   recibidas e instaladas ([SaveStore.ownMirrorHashes]). Vacío = sin historial (regla por fecha).
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

    /**
     * H9: el espejo SAF se reescribe con `"wt"` (no atómico). Si el proceso muere a mitad, queda un prefijo estricto de la
     * escritura intentada. Si el espejo es un prefijo estricto de la local y la local es la última escritura intentada
     * ([pendingFirst]), es nuestra escritura cortada: OWN_OLDER (gana la local), nunca un «cambio externo» que instale el
     * prefijo.
     */
    fun isTruncatedOwnWrite(local: ByteArray, mirror: ByteArray, localHash: String, pendingFirst: String?): Boolean =
        pendingFirst != null && pendingFirst == localHash && mirror.size < local.size &&
            mirror.indices.all { mirror[it] == local[it] }

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

    // MARK: copias en conflicto del proveedor (ND20 l, = iOS)

    private val ORDINAL = Regex("""^(.+?)(?: (\d+)| \((\d+)\))$""")
    private val PHRASES = listOf("conflicted copy", "copia en conflicto")

    /**
     * ¿Es [name] una copia en conflicto que el proveedor creó para el espejo `<base>.sav`? Formas: `X 2.sav` (n ≥ 2,
     * iCloud / Drive), `X (1).sav` (n ≥ 1, Drive, OneDrive), `X.sync-conflict-…sav` (Syncthing) y `X (… conflicted copy …)`
     * / «copia en conflicto» (Dropbox). Sin distinguir mayúsculas. No lo es el propio `<base>.sav` ni un nombre que es la
     * partida de OTRO juego de la carpeta ([otherGameBases]: bases de los ROMs hermanos, p. ej. `X 2` si existe `X 2.gb`).
     * Se listan en Ajustes › Partidas; **nunca** se borran.
     */
    fun isProviderConflictCopy(base: String, name: String, otherGameBases: Set<String> = emptySet()): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        val b = base.lowercase(Locale.ROOT)
        if (!lower.endsWith(".sav") || lower == "$b.sav") return false
        val stem = lower.removeSuffix(".sav")
        if (otherGameBases.any { it.lowercase(Locale.ROOT) == stem }) return false
        if (stem.startsWith("$b.sync-conflict-")) return true
        if (stem.startsWith("$b ") && PHRASES.any { it in stem }) return true
        val m = ORDINAL.matchEntire(stem) ?: return false
        if (m.groupValues[1] != b) return false
        val n = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return false
        return if (m.groupValues[2].isNotEmpty()) n >= 2 else n >= 1
    }

    /** `X (… conflicted copy …)` / `X (copia en conflicto …)` de Dropbox, con lo que haya antes de la frase («Joel's»). */
    private val DROPBOX = Regex("""^(.+?) \([^()]*(?:conflicted copy|copia en conflicto)[^()]*\)$""", RegexOption.IGNORE_CASE)

    /**
     * ND20 (l): el nombre de juego de una copia en conflicto (para buscar el juego de un `.sav` abierto con «Abrir con»):
     * `X 2`, `X (1)`, `X.sync-conflict-…`, `X (conflicted copy …)` y `X (Joel's conflicted copy …)` → `X` (= iOS
     * `ConflictCopies.originalStem`). Otro nombre se devuelve tal cual.
     */
    fun stripConflictSuffix(stem: String): String {
        val i = stem.lowercase(Locale.ROOT).indexOf(".sync-conflict-")
        if (i > 0) return stem.substring(0, i)
        DROPBOX.matchEntire(stem)?.let { return it.groupValues[1] }
        val m = ORDINAL.matchEntire(stem) ?: return stem
        val n = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return stem
        val ok = if (m.groupValues[2].isNotEmpty()) n >= 2 else n >= 1
        return if (ok) m.groupValues[1] else stem
    }

    /**
     * H5: los juegos de un `.sav` crudo abierto con «Abrir con»: primero los que se llaman EXACTAMENTE como el archivo; solo
     * si no hay ninguno, los que se llaman como el archivo sin el sufijo de copia en conflicto (`X 2.sav` es la partida de
     * `X 2.gb` si existe; si no, una copia en conflicto de `X`). Sin distinguir mayúsculas.
     */
    fun <T> gamesForRawSave(fileStem: String, games: List<T>, gameStem: (T) -> String): List<T> {
        val exact = games.filter { gameStem(it).equals(fileStem, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        val original = stripConflictSuffix(fileStem)
        if (original == fileStem) return emptyList()
        return games.filter { gameStem(it).equals(original, ignoreCase = true) }
    }
}
