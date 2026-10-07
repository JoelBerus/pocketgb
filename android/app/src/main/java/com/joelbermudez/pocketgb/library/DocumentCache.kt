package com.joelbermudez.pocketgb.library

import kotlinx.serialization.Serializable

/**
 * N1a · sello de un documento de la carpeta tal como lo da el escaneo, **sin calcular su huella**: nombre, tamaño, fecha
 * de modificación, id de documento e identidad de la cabecera (N1-H1, los 28 bytes 0x134–0x14F que el escáner ya lee).
 * En SAF `COLUMN_SIZE` y `COLUMN_LAST_MODIFIED` pueden faltar o ser nulos, y un documento remoto sin descargar no da
 * cabecera: entonces el sello está incompleto y no sirve para reconocer el documento en otra ruta.
 */
@Serializable
data class DocumentStamp(
    val name: String,
    /** Bytes; `null` si el proveedor no lo da. */
    val size: Long? = null,
    /** Epoch ms; `null` si el proveedor no la da. */
    val lastModified: Long? = null,
    /**
     * `COLUMN_DOCUMENT_ID`. Solo es la clave de la caché de cabeceras ([HeaderCache]): no sirve para reconocer un
     * movimiento (en ExternalStorage es la ruta y cambia al mover) y Drive puede cambiarlo sin tocar el archivo.
     */
    val documentId: String? = null,
    /** N1-H1: [RomHeader.identity] de la cabecera; `null` si no se pudo leer. */
    val header: String? = null,
) {
    /** Lo que se compara para reconocer el documento en otra ruta: nombre, tamaño, fecha y cabecera (sin el id). */
    internal val identity: DocumentStamp get() = if (documentId == null) this else copy(documentId = null)

    /** Clave de la regla de renombrado: tamaño, fecha y cabecera (sin nombre ni id). */
    internal val renameKey: Triple<Long?, Long?, String?> get() = Triple(size, lastModified, header)

    /** Con tamaño, fecha y cabecera conocidos: puede reconocer el documento en otra ruta. */
    val isComplete: Boolean get() = (size ?: 0L) > 0L && (lastModified ?: 0L) > 0L && header != null

    /**
     * En la misma ruta, el contenido es otro: cambió el tamaño o la cabecera (N1-H4). Una fecha o un id de documento
     * distintos con el mismo tamaño y la misma cabecera no bastan (Drive puede cambiarlos sin tocar el archivo).
     */
    fun differsFrom(other: DocumentStamp): Boolean =
        (size != null && other.size != null && size != other.size) ||
            (header != null && other.header != null && header != other.header)

    /** La misma ruta volvió (lápida): mismo tamaño y misma cabecera, ambos conocidos. */
    internal fun sameContentAs(other: DocumentStamp): Boolean =
        size != null && size > 0 && size == other.size && header != null && header == other.header

    companion object {
        /** El escáner pone 0 cuando el tamaño es desconocido (`SafDocumentTree.knownSize`). */
        fun of(entry: RomEntry) = DocumentStamp(
            name = entry.fileName,
            size = entry.sizeBytes.takeIf { it > 0 },
            lastModified = entry.lastModified?.takeIf { it > 0 },
            documentId = entry.documentId,
            header = entry.headerKey,
        )
    }
}

/**
 * N1-H4 · «lápida» de una ruta que desapareció en un escaneo completo sin reconocerse como movida: guarda su sello, su
 * huella y si ya se había visto, para recuperarlos si el documento vuelve (una carpeta que Drive dio vacía, un juego
 * apartado en `_Revisar/` y devuelto). Acotadas por [Tombstones.MAX_ENTRIES] y [Tombstones.MAX_AGE_MS].
 */
@Serializable
data class Tombstone(
    val path: String,
    val stamp: DocumentStamp,
    val fingerprint: String? = null,
    val known: Boolean = false,
    /** Epoch ms del escaneo en que desapareció. */
    val goneAt: Long = 0,
)

object Tombstones {
    const val MAX_ENTRIES = 200
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
}

/**
 * N1a · qué rutas nuevas son documentos ya vistos en otra ruta, mirando solo los sellos. Conservador: ante cualquier
 * ambigüedad no se reconoce nada (el juego recupera sus datos al abrirlo o ver su detalle, cuando se calcula su huella).
 */
object MoveDetection {
    /**
     * @param previous sellos del escaneo anterior, por ruta.
     * @param current sellos del escaneo actual, por ruta.
     * @return ruta desaparecida → ruta nueva. Una ruta desaparecida se reconoce si hay **exactamente una** ruta nueva con
     * su mismo (nombre, tamaño, fecha, cabecera) y ninguna otra desaparecida comparte ese sello (regla 1); o, si cambió de
     * nombre, si es la única desaparecida y la única nueva con ese (tamaño, fecha, cabecera) (regla 2). Sin tamaño, fecha
     * o cabecera no se reconoce nada: un ROM distinto con el mismo tamaño y fecha tiene otra cabecera (N1-H1).
     */
    fun detect(previous: Map<String, DocumentStamp>, current: Map<String, DocumentStamp>): Map<String, String> {
        val gone = previous.filter { (path, stamp) -> path !in current && stamp.isComplete }
        val appeared = current.filter { (path, stamp) -> path !in previous && stamp.isComplete }
        return match(gone, appeared)
    }

    /** Las dos reglas sobre conjuntos ya filtrados de desaparecidas y nuevas (también sirve para las lápidas). */
    internal fun match(gone: Map<String, DocumentStamp>, appeared: Map<String, DocumentStamp>): Map<String, String> {
        if (gone.isEmpty() || appeared.isEmpty()) return emptyMap()
        val moves = LinkedHashMap<String, String>()

        // 1) Mismo nombre, tamaño, fecha y cabecera (movido de carpeta).
        val appearedByStamp = appeared.keys.groupBy { appeared.getValue(it).identity }
        gone.keys.groupBy { gone.getValue(it).identity }.forEach { (stamp, from) ->
            val to = appearedByStamp[stamp]
            if (from.size == 1 && to?.size == 1) moves[from.single()] = to.single()
        }

        // 2) Mismo tamaño, fecha y cabecera con otro nombre (renombrado), solo si nadie más los comparte.
        val appearedByKey = appeared.keys.groupBy { appeared.getValue(it).renameKey }
        gone.keys.groupBy { gone.getValue(it).renameKey }.forEach { (k, from) ->
            val to = appearedByKey[k]
            if (from.size == 1 && to?.size == 1 && from.single() !in moves && to.single() !in moves.values) {
                moves[from.single()] = to.single()
            }
        }
        return moves
    }
}

/**
 * N1a · aplica un escaneo a las preferencias sin calcular ninguna huella:
 * - Con el escaneo **completo** ([ScanStats.complete]), las rutas reconocidas como movidas ([MoveDetection]) se llevan su
 *   huella y sus registros por ruta (favorito, fecha, oculto y alias provisionales, y «ya visto»). Las que desaparecen
 *   sin reconocerse dejan una **lápida** ([Tombstone]) y se olvida su huella (lo guardado por huella no se toca). Una ruta
 *   que aparece y coincide con una lápida (la misma ruta con el mismo tamaño y cabecera, u otra ruta con el sello entero
 *   y sin ambigüedad) recupera su huella, su «ya visto» y sus registros por ruta (N1-H4).
 * - Una huella heredada así queda **sin confirmar** ([LibraryPreferencesData.inferredFingerprints]) hasta que se lee el
 *   ROM (abrirlo o ver su detalle); los ajustes del juego la confirman antes de escribir (N1-H1).
 * - Una ruta que sigue en su sitio con otro tamaño u otra cabecera olvida su huella: es otro ROM. Riesgo residual: otro
 *   ROM con el mismo tamaño y la misma cabecera (p. ej. un parche aplicado encima que no recalcula los checksums) conserva
 *   la huella anterior hasta que se abre (abrir siempre calcula la huella real del ROM).
 * - Con un escaneo incompleto (una carpeta falló, vino a medias o se llegó al tope) solo se anotan los sellos vistos: nada
 *   se traslada ni se olvida. Un listado vacío no cambia nada (un proveedor en la nube con un fallo pasajero).
 * Si no hay nada que cambiar devuelve un valor igual (no provoca escrituras).
 */
fun LibraryPreferencesData.reconciled(
    entries: List<RomEntry>,
    complete: Boolean,
    now: Long = System.currentTimeMillis(),
): LibraryPreferencesData {
    if (entries.isEmpty()) return this
    val current = entries.associate { it.id to DocumentStamp.of(it) }
    var data = this
    val moves = if (complete) MoveDetection.detect(documents, current) else emptyMap()
    for ((from, to) in moves) data = data.withPathMoved(from, to)

    var tombstones = data.tombstones
    if (complete) {
        // Lápidas de lo que desaparece sin reconocerse (con algo con que reconocerlo después).
        val gone = documents.keys - current.keys - moves.keys
        val fresh = gone.mapNotNull { path ->
            val stamp = documents.getValue(path)
            if ((stamp.size ?: 0L) <= 0L || stamp.header == null) return@mapNotNull null
            Tombstone(path, stamp, data.fingerprints[path], path in data.knownIds, now)
        }
        tombstones = tombstones.filter { it.path !in gone } + fresh

        // Rutas nuevas (ni vistas antes ni recién movidas) que vuelven: primero la misma ruta, después otra sin ambigüedad.
        val appeared = current.keys - documents.keys - moves.values.toSet()
        val used = HashSet<Tombstone>()
        val restoredPaths = HashSet<String>()
        for (path in appeared) {
            val tomb = tombstones.firstOrNull { it.path == path && it.stamp.sameContentAs(current.getValue(path)) } ?: continue
            data = data.withRestored(tomb, path, current.keys)
            used += tomb
            restoredPaths += path
        }
        val remaining = tombstones.filter { it !in used && it.stamp.isComplete }
        val appearedLeft = (appeared - restoredPaths).associateWith { current.getValue(it) }.filterValues { it.isComplete }
        val byKey = remaining.associateBy { "lápida:" + it.path }
        for ((key, to) in MoveDetection.match(byKey.mapValues { it.value.stamp }, appearedLeft)) {
            val tomb = byKey.getValue(key)
            data = data.withRestored(tomb, to, current.keys)
            used += tomb
        }
        tombstones = tombstones.filter { it !in used && now - it.goneAt <= Tombstones.MAX_AGE_MS }
            .sortedByDescending { it.goneAt }
            .take(Tombstones.MAX_ENTRIES)
    }

    val changed = current.filter { (path, stamp) -> documents[path]?.differsFrom(stamp) == true }.keys
    val fingerprints = if (complete) data.fingerprints.filterKeys { it in current } else data.fingerprints
    val inferred = if (complete) data.inferredFingerprints.filter { it in current }.toSet() else data.inferredFingerprints
    return data.copy(
        fingerprints = if (changed.isEmpty()) fingerprints else fingerprints - changed,
        inferredFingerprints = if (changed.isEmpty()) inferred else inferred - changed,
        documents = if (complete) current else data.documents + current,
        tombstones = tombstones,
    )
}

/** Recupera lo que guardaba la lápida [tomb] en la ruta [to]. */
private fun LibraryPreferencesData.withRestored(tomb: Tombstone, to: String, present: Set<String>): LibraryPreferencesData {
    // Los registros por ruta siguen bajo la ruta vieja (nunca se podan): se mueven si esa ruta no está ocupada.
    val moved = if (tomb.path != to && tomb.path !in present) withPathMoved(tomb.path, to) else this
    val fingerprint = tomb.fingerprint
    return moved.copy(
        fingerprints = if (fingerprint != null) moved.fingerprints + (to to fingerprint) else moved.fingerprints,
        inferredFingerprints = if (fingerprint != null) moved.inferredFingerprints + to else moved.inferredFingerprints,
        knownIds = if (tomb.known) moved.knownIds + to else moved.knownIds,
    )
}

/** Lleva lo que se recuerda de la ruta [from] a la ruta [to] (el mismo documento, movido o renombrado). */
internal fun LibraryPreferencesData.withPathMoved(from: String, to: String): LibraryPreferencesData {
    fun <V> Map<String, V>.moved(): Map<String, V> {
        val value = this[from] ?: return this
        return this - from + (to to value)
    }
    fun Set<String>.moved(): Set<String> = if (from in this) this - from + to else this
    val movedFingerprint = fingerprints[from] != null
    return copy(
        // Una huella vieja anotada en la ruta nueva no vale: manda la del documento movido (o ninguna).
        fingerprints = (fingerprints - to).moved(),
        // La huella trasladada queda sin confirmar hasta que se lea el ROM (N1-H1).
        inferredFingerprints = (inferredFingerprints - from - to) + if (movedFingerprint) setOf(to) else emptySet(),
        favorites = favorites.moved(),
        lastPlayed = lastPlayed.moved(),
        hiddenPaths = hiddenPaths.moved(),
        aliasesByPath = aliasesByPath.moved(),
        knownIds = knownIds.moved(),
    )
}
