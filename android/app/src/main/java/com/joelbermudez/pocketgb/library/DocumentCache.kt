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
    /**
     * N1-V2-H1: [header] no se leyó en este escaneo (documento sin descargar o ilegible): es la del escaneo anterior en la
     * misma ruta, con el mismo tamaño. Sirve para comparar, pero no alimenta la caché de cabeceras.
     */
    val headerCarried: Boolean = false,
) {
    /** Lo que se compara para reconocer el documento en otra ruta: nombre, tamaño, fecha y cabecera (sin el id). */
    internal val identity: DocumentStamp get() = copy(documentId = null, headerCarried = false)

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
    /** N1-V2-H4: registros provisionales por ruta (sin huella) que tenía; vuelven solo si la lápida coincide. */
    val favorite: Boolean = false,
    val lastPlayed: Long? = null,
    val hidden: Boolean = false,
    val alias: String? = null,
) {
    /**
     * El documento vuelve a [path] con [current]: mismo tamaño y cabecera; o, si la lápida no tenía cabecera, mismo
     * tamaño y fecha, y tampoco ahora hay cabecera.
     */
    internal fun matchesAt(path: String, current: DocumentStamp): Boolean {
        if (path != this.path) return false
        if (stamp.sameContentAs(current)) return true
        return stamp.header == null && current.header == null && (stamp.size ?: 0L) > 0L && stamp.size == current.size &&
            (stamp.lastModified ?: 0L) > 0L && stamp.lastModified == current.lastModified
    }
}

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
 *   sin reconocerse dejan una **lápida** ([Tombstone]) con su huella y sus registros por ruta (N1-V2-H4), que dejan de
 *   estar en la ruta. Una ruta que aparece y coincide con una lápida (la misma ruta con el mismo contenido, u otra ruta
 *   con el sello entero y sin ambigüedad) los recupera (N1-H4).
 * - Una ruta que sigue en su sitio con otro tamaño u otra cabecera es otro ROM: olvida su huella, deja de estar «vista» y
 *   sus registros por ruta pasan a una lápida (N1-V2-H4), también con un escaneo incompleto (la ruta sí se listó).
 * - Si en la misma ruta cambian la fecha o el id, o esta vez no se pudo leer la cabecera, la huella se mantiene pero pasa
 *   a **sin confirmar** (N1-V2-H1). Una cabecera que no se pudo leer se toma del escaneo anterior (mismo tamaño), marcada
 *   como heredada para que no alimente la caché de cabeceras.
 * - Una huella heredada (movimiento, lápida o cambio leve) queda en [LibraryPreferencesData.inferredFingerprints] hasta que
 *   se lee el ROM (abrirlo o ver su detalle); los ajustes del juego la confirman antes de escribir (N1-H1).
 * - Con un escaneo incompleto (una carpeta falló, vino a medias o se llegó al tope) solo se refrescan los sellos de rutas ya
 *   conocidas: nada se traslada ni se olvida, y las rutas nuevas no se anotan, para que el siguiente escaneo completo
 *   reconozca el movimiento (N1-V2-H2). Un listado vacío no cambia nada.
 * Riesgos residuales: otro ROM con el mismo tamaño y la misma cabecera en la misma ruta (un parche que no recalcula los
 * checksums) conserva la huella hasta que se lee; y la caché de cabeceras (N1-H6) da por buena la cabecera de un
 * documento con el mismo id, tamaño y fecha (en ms) aunque su contenido hubiera cambiado. Abrir siempre calcula la huella
 * real, así que nunca se abre la partida de otro juego.
 * Si no hay nada que cambiar devuelve un valor igual (no provoca escrituras).
 */
fun LibraryPreferencesData.reconciled(
    entries: List<RomEntry>,
    complete: Boolean,
    now: Long = System.currentTimeMillis(),
): LibraryPreferencesData {
    if (entries.isEmpty()) return this
    val read = entries.associate { it.id to DocumentStamp.of(it) }
    // N1-V2-H1: sin cabecera esta vez, se conserva la del escaneo anterior en la misma ruta si el tamaño no cambió.
    val current = read.mapValues { (path, stamp) ->
        val previous = documents[path]
        if (stamp.header == null && previous?.header != null && (previous.size == null || stamp.size == null || previous.size == stamp.size)) {
            stamp.copy(header = previous.header, headerCarried = true)
        } else {
            stamp
        }
    }
    var data = this
    val moves = if (complete) MoveDetection.detect(documents, current) else emptyMap()
    for ((from, to) in moves) data = data.withPathMoved(from, to)

    // Misma ruta, otro contenido (otro tamaño u otra cabecera): es otro ROM.
    val changed = current.filter { (path, stamp) -> documents[path]?.differsFrom(stamp) == true }.keys
    // Misma ruta, cambio leve: otra fecha u otro id, o la cabecera no se pudo leer. La huella sigue, pero sin confirmar.
    val softened = current.filter { (path, stamp) ->
        val previous = documents[path] ?: return@filter false
        path !in changed && path in data.fingerprints && (
            read.getValue(path).header == null ||
                (previous.lastModified != null && stamp.lastModified != null && previous.lastModified != stamp.lastModified) ||
                (previous.documentId != null && stamp.documentId != null && previous.documentId != stamp.documentId)
            )
    }.keys
    val gone = if (complete) documents.keys - current.keys - moves.keys else emptySet()
    val leaving = gone + changed
    val appeared = if (complete) current.keys - documents.keys - moves.values.toSet() else emptySet()

    // Lápidas que coinciden con lo que hay ahora en su misma ruta (vuelve el documento o vuelve el contenido anterior).
    val samePath = (appeared + changed).mapNotNull { path ->
        data.tombstones.firstOrNull { it.matchesAt(path, current.getValue(path)) }?.let { path to it }
    }.toMap()
    // Lo que se va (o es otro ROM) deja su lápida con huella, «ya visto» y registros por ruta, que dejan la ruta.
    val fresh = leaving.map { path -> data.tombstoneOf(path, documents.getValue(path), now) }
    data = data.withoutPathRecords(leaving).copy(
        fingerprints = data.fingerprints - changed,
        inferredFingerprints = data.inferredFingerprints - changed,
        knownIds = data.knownIds - changed,
    )
    var tombstones = data.tombstones.filter { it.path !in leaving && it !in samePath.values } + fresh
    for ((path, tomb) in samePath) data = data.withRestored(tomb, path)
    if (complete) {
        // Otra ruta: el sello entero y sin ambigüedad.
        val left = (appeared - samePath.keys).associateWith { current.getValue(it) }.filterValues { it.isComplete }
        val candidates = tombstones.filter { it.stamp.isComplete }.associateBy { "lápida:" + it.path + "@" + it.goneAt }
        val used = HashSet<Tombstone>()
        for ((key, to) in MoveDetection.match(candidates.mapValues { it.value.stamp }, left)) {
            val tomb = candidates.getValue(key)
            data = data.withRestored(tomb, to)
            used += tomb
        }
        tombstones = tombstones.filter { it !in used }
    }
    tombstones = tombstones.filter { now - it.goneAt <= Tombstones.MAX_AGE_MS }
        .sortedByDescending { it.goneAt }
        .take(Tombstones.MAX_ENTRIES)

    val fingerprints = if (complete) data.fingerprints.filterKeys { it in current } else data.fingerprints
    val inferred = (if (complete) data.inferredFingerprints.filter { it in current }.toSet() else data.inferredFingerprints) +
        softened.filter { it in fingerprints }
    return data.copy(
        fingerprints = fingerprints,
        inferredFingerprints = inferred,
        // N1-V2-H2: con un escaneo incompleto solo se refrescan las rutas que ya se conocían.
        documents = if (complete) current else data.documents + current.filterKeys { it in data.documents },
        tombstones = tombstones,
    )
}

/** Lápida de [path] con su último sello, su huella, si se había visto y sus registros por ruta. */
private fun LibraryPreferencesData.tombstoneOf(path: String, stamp: DocumentStamp, now: Long) = Tombstone(
    path = path,
    stamp = stamp,
    fingerprint = fingerprints[path],
    known = path in knownIds,
    goneAt = now,
    favorite = path in favorites,
    lastPlayed = lastPlayed[path],
    hidden = path in hiddenPaths,
    alias = aliasesByPath[path],
)

/** Quita de [paths] los registros provisionales por ruta (pasan a sus lápidas). */
private fun LibraryPreferencesData.withoutPathRecords(paths: Set<String>): LibraryPreferencesData {
    if (paths.isEmpty()) return this
    return copy(
        favorites = favorites - paths,
        lastPlayed = lastPlayed - paths,
        hiddenPaths = hiddenPaths - paths,
        aliasesByPath = aliasesByPath - paths,
    )
}

/** Recupera en la ruta [to] lo que guardaba la lápida [tomb]: huella (sin confirmar), «ya visto» y registros por ruta. */
private fun LibraryPreferencesData.withRestored(tomb: Tombstone, to: String): LibraryPreferencesData {
    val fingerprint = tomb.fingerprint
    val keepOwn = fingerprints[to] != null && to !in inferredFingerprints
    return copy(
        fingerprints = if (fingerprint != null && !keepOwn) fingerprints + (to to fingerprint) else fingerprints,
        inferredFingerprints = if (fingerprint != null && !keepOwn) inferredFingerprints + to else inferredFingerprints,
        knownIds = if (tomb.known) knownIds + to else knownIds,
        favorites = if (tomb.favorite) favorites + to else favorites,
        lastPlayed = tomb.lastPlayed?.let { lastPlayed + (to to it) } ?: lastPlayed,
        hiddenPaths = if (tomb.hidden) hiddenPaths + to else hiddenPaths,
        aliasesByPath = tomb.alias?.let { aliasesByPath + (to to it) } ?: aliasesByPath,
    )
}

/** Lleva lo que se recuerda de la ruta [from] a la ruta [to] (el mismo documento, movido o renombrado). */
internal fun LibraryPreferencesData.withPathMoved(from: String, to: String): LibraryPreferencesData {
    fun <V> Map<String, V>.moved(): Map<String, V> {
        val value = this[from] ?: return this
        return this - from + (to to value)
    }
    fun Set<String>.moved(): Set<String> = if (from in this) this - from + to else this
    // N1-V2-H2: si la ruta nueva ya tiene una huella confirmada (se abrió antes de reconocer el movimiento), manda esa.
    val keepOwn = fingerprints[to] != null && to !in inferredFingerprints
    val movedFingerprint = fingerprints[from]
    return copy(
        fingerprints = when {
            keepOwn -> fingerprints - from
            movedFingerprint != null -> fingerprints - from + (to to movedFingerprint)
            else -> fingerprints - from - to
        },
        // La huella trasladada queda sin confirmar hasta que se lea el ROM (N1-H1).
        inferredFingerprints = (inferredFingerprints - from - to) + if (!keepOwn && movedFingerprint != null) setOf(to) else emptySet(),
        favorites = favorites.moved(),
        lastPlayed = lastPlayed.moved(),
        hiddenPaths = hiddenPaths.moved(),
        aliasesByPath = aliasesByPath.moved(),
        knownIds = knownIds.moved(),
    )
}
