package com.joelbermudez.pocketgb.library

import kotlinx.serialization.Serializable

/**
 * N1a · sello de un documento de la carpeta tal como lo lista el proveedor, **sin leer el archivo**: nombre, tamaño y
 * fecha de modificación. En SAF `COLUMN_SIZE` y `COLUMN_LAST_MODIFIED` pueden faltar o ser nulos: entonces el sello
 * está incompleto y no sirve para reconocer el documento en otra ruta.
 */
@Serializable
data class DocumentStamp(
    val name: String,
    /** Bytes; `null` si el proveedor no lo da. */
    val size: Long? = null,
    /** Epoch ms; `null` si el proveedor no la da. */
    val lastModified: Long? = null,
) {
    /** Con tamaño y fecha conocidos: puede reconocer el documento en otra ruta. */
    val isComplete: Boolean get() = (size ?: 0L) > 0L && (lastModified ?: 0L) > 0L

    /** En la misma ruta, el proveedor da otro tamaño u otra fecha: el contenido puede ser otro. */
    fun differsFrom(other: DocumentStamp): Boolean =
        (size != null && other.size != null && size != other.size) ||
            (lastModified != null && other.lastModified != null && lastModified != other.lastModified)

    companion object {
        /** El escáner pone 0 cuando el tamaño es desconocido (`SafDocumentTree.knownSize`). */
        fun of(entry: RomEntry) = DocumentStamp(
            name = entry.fileName,
            size = entry.sizeBytes.takeIf { it > 0 },
            lastModified = entry.lastModified?.takeIf { it > 0 },
        )
    }
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
     * su mismo (nombre, tamaño, fecha) y ninguna otra desaparecida comparte ese sello; o, si cambió de nombre, si es la
     * única desaparecida y la única nueva con ese (tamaño, fecha). Sin tamaño o sin fecha no se reconoce nada.
     */
    fun detect(previous: Map<String, DocumentStamp>, current: Map<String, DocumentStamp>): Map<String, String> {
        val gone = previous.filter { (path, stamp) -> path !in current && stamp.isComplete }
        val appeared = current.filter { (path, stamp) -> path !in previous && stamp.isComplete }
        if (gone.isEmpty() || appeared.isEmpty()) return emptyMap()
        val moves = LinkedHashMap<String, String>()

        // 1) Mismo nombre, tamaño y fecha (movido de carpeta).
        val appearedByStamp = appeared.keys.groupBy { appeared.getValue(it) }
        gone.keys.groupBy { gone.getValue(it) }.forEach { (stamp, from) ->
            val to = appearedByStamp[stamp]
            if (from.size == 1 && to?.size == 1) moves[from.single()] = to.single()
        }

        // 2) Mismo tamaño y fecha con otro nombre (renombrado), solo si nadie más comparte ese tamaño y esa fecha.
        fun key(stamp: DocumentStamp) = stamp.size to stamp.lastModified
        val appearedByKey = appeared.keys.groupBy { key(appeared.getValue(it)) }
        gone.keys.groupBy { key(gone.getValue(it)) }.forEach { (k, from) ->
            val to = appearedByKey[k]
            if (from.size == 1 && to?.size == 1 && from.single() !in moves && to.single() !in moves.values) {
                moves[from.single()] = to.single()
            }
        }
        return moves
    }
}

/**
 * N1a · aplica un escaneo a las preferencias sin leer ningún ROM:
 * - Con el escaneo **completo** ([ScanStats.complete]), las rutas reconocidas como movidas ([MoveDetection]) se llevan su
 *   huella y sus registros por ruta (favorito, fecha, oculto y alias provisionales, y «ya visto»), y se olvida la huella
 *   de las rutas que ya no están (lo guardado por huella no se toca: vuelve en cuanto se conoce la huella otra vez).
 * - Una ruta que sigue en su sitio con otro tamaño u otra fecha olvida su huella: puede ser otro ROM con el mismo nombre.
 * - Con un escaneo incompleto (una carpeta falló o se llegó al tope) solo se anotan los sellos vistos: nada se traslada
 *   ni se olvida. Un listado vacío no cambia nada (un proveedor en la nube con un fallo pasajero).
 * Si no hay nada que cambiar devuelve un valor igual (no provoca escrituras).
 */
fun LibraryPreferencesData.reconciled(entries: List<RomEntry>, complete: Boolean): LibraryPreferencesData {
    if (entries.isEmpty()) return this
    val current = entries.associate { it.id to DocumentStamp.of(it) }
    var data = this
    val moves = if (complete) MoveDetection.detect(documents, current) else emptyMap()
    for ((from, to) in moves) data = data.withPathMoved(from, to)
    val changed = current.filter { (path, stamp) -> documents[path]?.differsFrom(stamp) == true }.keys
    val fingerprints = if (complete) data.fingerprints.filterKeys { it in current } else data.fingerprints
    return data.copy(
        fingerprints = if (changed.isEmpty()) fingerprints else fingerprints - changed,
        documents = if (complete) current else data.documents + current,
    )
}

/** Lleva lo que se recuerda de la ruta [from] a la ruta [to] (el mismo documento, movido o renombrado). */
internal fun LibraryPreferencesData.withPathMoved(from: String, to: String): LibraryPreferencesData {
    fun <V> Map<String, V>.moved(): Map<String, V> {
        val value = this[from] ?: return this
        return this - from + (to to value)
    }
    fun Set<String>.moved(): Set<String> = if (from in this) this - from + to else this
    return copy(
        // Una huella vieja anotada en la ruta nueva no vale: manda la del documento movido (o ninguna).
        fingerprints = (fingerprints - to).moved(),
        favorites = favorites.moved(),
        lastPlayed = lastPlayed.moved(),
        hiddenPaths = hiddenPaths.moved(),
        aliasesByPath = aliasesByPath.moved(),
        knownIds = knownIds.moved(),
    )
}
