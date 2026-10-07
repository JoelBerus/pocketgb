package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * N6 · momentos de un juego (§3.3, ND13) en `moments/<huella>/`, por dispositivo (ND12):
 *
 * - **Momento** (`m-<id>.*`): estado del núcleo (`.state`), RAM del cartucho del instante (`.sav`, para recuperar la
 *   partida aunque el estado ya no cargue), miniatura (`.png`) y, en `index.json`, nombre, etiquetas, colección, nota,
 *   fecha, tiempo jugado y configuración (modelo, paleta; en GBA, tipo de partida, reloj y BIOS).
 * - **Anillo «Antes de cargar»** (`b-<id>.*`): las 3 últimas posiciones de antes de cargar un momento, FUERA de la
 *   rotación de 5 backups del `.sav`. El 4.º empuja al más antiguo.
 *
 * Regla dura 6: cada archivo se escribe con temporal + `fsync` + `rename`, y el índice es el punto de confirmación (se
 * escribe el último al crear y el primero al borrar). Un archivo sin entrada en el índice es una escritura que no llegó a
 * confirmarse y [recoverOrphans] lo retira; un índice ilegible no borra nada: se aparta y se reconstruye desde los
 * archivos. Las ranuras 1–4 y RESCUE se migran sin pérdida con [migrateSlots].
 */
class MomentStore(
    val directory: File,
    private val ops: SaveFileOps = PosixSaveFileOps,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "").take(16) },
) {
    constructor(root: File, fingerprint: String, ops: SaveFileOps = PosixSaveFileOps) : this(File(root, fingerprint), ops)

    enum class Kind(val prefix: String) { MOMENT("m-"), BEFORE_LOAD("b-") }

    /** Un momento o una entrada del anillo. Los textos ya vienen normalizados ([normalized]). */
    @Serializable
    data class Moment(
        val id: String,
        val name: String,
        val tags: List<String> = emptyList(),
        val collection: String? = null,
        val note: String = "",
        val createdMs: Long,
        /** Tiempo de juego acumulado del juego al crearlo (N6), o `null` si no se sabía. */
        val playTimeMs: Long? = null,
        /** Configuración con la que se creó (claves: `console`, `model`, `palette`, `gbaSaveType`, `gbaRtc`, `gbaBios`). */
        val config: Map<String, String> = emptyMap(),
        val hasState: Boolean = true,
        val hasSram: Boolean = false,
        val hasThumbnail: Boolean = false,
        /** Migrado de una ranura antigua (`slot1`…`slot4`, `rescue`, `rescue-…`) y SHA-256 de su estado. */
        val origin: String? = null,
        val originSha: String? = null,
    )

    @Serializable
    private data class Index(
        val version: Int = 1,
        val moments: List<Moment> = emptyList(),
        val beforeLoad: List<Moment> = emptyList(),
    )

    /** Momentos (más reciente primero) y anillo «Antes de cargar» (más reciente primero). */
    data class Snapshot(val moments: List<Moment>, val beforeLoad: List<Moment>) {
        fun find(kind: Kind, id: String): Moment? = (if (kind == Kind.MOMENT) moments else beforeLoad).firstOrNull { it.id == id }
    }

    /** Lo que se guarda de un instante: estado, RAM del cartucho y miniatura (cualquiera puede faltar salvo uno de los dos primeros). */
    class Capture(val state: ByteArray?, val sram: ByteArray?, val thumbnail: ByteArray?) {
        init {
            require(state != null || sram != null) { "Un momento necesita estado o partida" }
        }
    }

    companion object {
        const val RING_SIZE = 3
        const val MAX_NAME = 80
        const val MAX_NOTE = 2_000
        const val MAX_TAGS = 12
        const val MAX_TAG = 40
        const val MAX_COLLECTION = 40
        private const val MAX_INDEX_BYTES = 4 shl 20
        private const val INDEX = "index.json"
        private val ID = Regex("[0-9a-z]{1,32}")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        /** Etiquetas sin espacios sobrantes, sin repetir (sin distinguir mayúsculas), acotadas. */
        fun normalizeTags(tags: List<String>): List<String> {
            val seen = HashSet<String>()
            return tags.map { it.trim().take(MAX_TAG) }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }.take(MAX_TAGS)
        }
    }

    private val lock: ReentrantLock = SaveLocks.forSave(directory, "#moments")
    private val indexFile get() = File(directory, INDEX)

    fun stateFile(kind: Kind, id: String) = File(directory, "${kind.prefix}${checkId(id)}.state")
    fun sramFile(kind: Kind, id: String) = File(directory, "${kind.prefix}${checkId(id)}.sav")
    fun thumbnailFile(kind: Kind, id: String) = File(directory, "${kind.prefix}${checkId(id)}.png")

    private fun checkId(id: String): String {
        require(ID.matches(id)) { "Id de momento no válido: $id" }
        return id
    }

    // ------------------------------------------------------------------ lectura

    /** Lanza [IOException] si no se puede leer el índice (no se toca nada). Sin índice: vacío. */
    fun snapshot(): Snapshot = lock.withLock {
        val index = readIndex() ?: throw IOException("El índice de momentos está dañado")
        // El anillo va en orden de inserción (más reciente primero), no por fecha: un reloj que retrocede no lo reordena.
        Snapshot(index.moments.sortedByDescending { it.createdMs }, index.beforeLoad)
    }

    fun loadState(kind: Kind, id: String): ByteArray = ops.readBytes(stateFile(kind, id), StateStore.MAX_STATE_BYTES)

    fun loadSram(kind: Kind, id: String): ByteArray? =
        if (ops.exists(sramFile(kind, id))) ops.readBytes(sramFile(kind, id), SaveStore.MAX_SAVE_BYTES) else null

    fun thumbnail(kind: Kind, id: String): ByteArray? = try {
        if (ops.exists(thumbnailFile(kind, id))) ops.readBytes(thumbnailFile(kind, id), StateStore.MAX_THUMBNAIL_BYTES) else null
    } catch (_: IOException) {
        null
    }

    // ------------------------------------------------------------------ escritura

    /** Crea un momento. Los archivos se confirman antes que el índice. */
    fun create(
        capture: Capture,
        name: String,
        config: Map<String, String> = emptyMap(),
        playTimeMs: Long? = null,
        tags: List<String> = emptyList(),
        collection: String? = null,
        note: String = "",
        createdMs: Long = now(),
    ): Moment = lock.withLock {
        val index = indexForWriting()
        val id = freshId(index)
        writeFiles(Kind.MOMENT, id, capture)
        val moment = normalized(
            Moment(
                id = id, name = name, tags = tags, collection = collection, note = note, createdMs = createdMs,
                playTimeMs = playTimeMs, config = config, hasState = capture.state != null, hasSram = capture.sram != null,
                hasThumbnail = capture.thumbnail != null,
            ),
        )
        writeIndex(index.copy(moments = index.moments + moment))
        moment
    }

    /**
     * Añade al anillo «Antes de cargar» la posición actual y retira la más antigua por orden de inserción si pasa de
     * [RING_SIZE] (su borrado es posterior a la confirmación del índice). La entrada nueva nunca se expulsa, ni tampoco
     * [protect] (la entrada del anillo que se está recuperando). [label] = qué se iba a cargar, para el nombre en la lista.
     */
    fun pushBeforeLoad(
        capture: Capture, label: String, config: Map<String, String> = emptyMap(), playTimeMs: Long? = null, protect: String? = null,
    ): Moment = pushBeforeLoadDeferred(capture, label, config, playTimeMs, protect).also { it.commit() }.entry

    /** Push cuyo borrado de las expulsadas espera a [commit] (tras confirmar la carga). Sin commit quedan huérfanas y
     *  [recoverOrphans] las retira al volver a abrir. */
    inner class PendingPush internal constructor(val entry: Moment, val evicted: List<String>) {
        fun commit() = lock.withLock { for (id in evicted) deleteFiles(Kind.BEFORE_LOAD, id) }
    }

    /**
     * Como [pushBeforeLoad], pero sin borrar los archivos de las expulsadas hasta [PendingPush.commit] (N6A-H2: si la
     * carga o la instalación fallan, nada recuperable se ha borrado). [protect] nunca sale expulsada.
     */
    fun pushBeforeLoadDeferred(
        capture: Capture, label: String, config: Map<String, String> = emptyMap(), playTimeMs: Long? = null, protect: String? = null,
    ): PendingPush =
        lock.withLock {
            val index = indexForWriting()
            val id = freshId(index)
            writeFiles(Kind.BEFORE_LOAD, id, capture)
            val entry = normalized(
                Moment(
                    id = id, name = label, createdMs = now(), playTimeMs = playTimeMs, config = config,
                    hasState = capture.state != null, hasSram = capture.sram != null, hasThumbnail = capture.thumbnail != null,
                ),
            )
            // N6A-H1: orden de inserción, nunca por `createdMs` (con el reloj hacia atrás la nueva saldría expulsada).
            // N6A-H2: la protegida ocupa plaza fija detrás de la nueva; se expulsan las demás más antiguas.
            val protected = index.beforeLoad.filter { it.id == protect }
            val ring = listOf(entry) + protected + index.beforeLoad.filter { it.id != protect }
            val keepIds = ring.take(RING_SIZE).mapTo(HashSet()) { it.id }
            val kept = (listOf(entry) + index.beforeLoad).filter { it.id in keepIds }
            val evicted = ring.filter { it.id !in keepIds }.map { it.id }
            writeIndex(index.copy(beforeLoad = kept))
            PendingPush(entry, evicted)
        }

    /** Renombra o edita etiquetas, colección y nota de un momento. Lanza [NoSuchElementException] si no existe. */
    fun update(id: String, name: String, tags: List<String>, collection: String?, note: String): Moment = lock.withLock {
        val index = indexForWriting()
        val current = index.moments.firstOrNull { it.id == id } ?: throw NoSuchElementException(id)
        val updated = normalized(current.copy(name = name, tags = tags, collection = collection, note = note))
        writeIndex(index.copy(moments = index.moments.map { if (it.id == id) updated else it }))
        updated
    }

    /** Borra un momento o una entrada del anillo: primero sale del índice (confirmado) y después se borran sus archivos. */
    fun delete(kind: Kind, id: String) = lock.withLock {
        val index = indexForWriting()
        val next = when (kind) {
            Kind.MOMENT -> index.copy(moments = index.moments.filter { it.id != id })
            Kind.BEFORE_LOAD -> index.copy(beforeLoad = index.beforeLoad.filter { it.id != id })
        }
        if (next != index) writeIndex(next)
        deleteFiles(kind, id)
    }

    /**
     * Al abrir: borra temporales y archivos que nunca llegaron al índice. Con el índice ilegible no se borra nada: se
     * aparta (`index.damaged-<fecha>.json`) y se reconstruye desde los archivos que haya.
     */
    fun recoverOrphans() = lock.withLock {
        if (!ops.exists(directory)) return@withLock
        for (name in ops.list(directory)) if (name.endsWith(".tmp")) ops.delete(File(directory, name))
        val index = indexForWriting()
        val known = index.moments.map { Kind.MOMENT.prefix + it.id } + index.beforeLoad.map { Kind.BEFORE_LOAD.prefix + it.id }
        for (name in ops.list(directory)) {
            val stem = stemOf(name) ?: continue
            if (stem !in known) ops.delete(File(directory, name))
        }
    }

    /**
     * Migra las ranuras 1–4, RESCUE y los rescates apartados (`rescue-*.state`) de [states] a momentos sin pérdida: se
     * copia el estado (y su captura), se confirma el índice con su origen y su SHA-256 y solo entonces se borra la
     * ranura. Si el proceso muere entre medias, la siguiente migración reconoce el SHA y solo borra la ranura.
     * [nameFor] da el nombre visible («Ranura 1», «Rescate»…). Devuelve cuántas ranuras migró.
     */
    fun migrateSlots(states: StateStore, nameFor: (origin: String) -> String): Int = lock.withLock {
        val sources = buildList {
            for (slot in StateSlot.MANUAL + StateSlot.RESCUE) add(slot.fileStem to states.stateFile(slot))
            if (ops.exists(states.directory)) {
                for (name in ops.list(states.directory).sorted()) {
                    if (name.startsWith("rescue-") && name.endsWith(".state")) add(name.removeSuffix(".state") to File(states.directory, name))
                }
            }
        }
        var migrated = 0
        for ((origin, file) in sources) {
            if (!ops.exists(file)) continue
            val state = ops.readBytes(file, StateStore.MAX_STATE_BYTES)
            val sha = sha256(state)
            val png = File(file.path.removeSuffix(".state") + ".png")
            val index = indexForWriting()
            if (index.moments.none { it.originSha == sha }) {
                val thumb = try { if (ops.exists(png)) ops.readBytes(png, StateStore.MAX_THUMBNAIL_BYTES) else null } catch (_: IOException) { null }
                val id = freshId(index)
                writeFiles(Kind.MOMENT, id, Capture(state, null, thumb))
                val moment = normalized(
                    Moment(
                        id = id, name = nameFor(origin), createdMs = ops.lastModified(file) ?: now(), hasState = true,
                        hasSram = false, hasThumbnail = thumb != null, origin = origin, originSha = sha,
                    ),
                )
                writeIndex(index.copy(moments = index.moments + moment))
            }
            ops.delete(file)
            try { ops.delete(png) } catch (_: IOException) {}
            migrated++
        }
        if (migrated > 0 && ops.exists(states.directory)) ops.syncDirectory(states.directory)
        migrated
    }

    // ------------------------------------------------------------------ interno

    private fun normalized(m: Moment) = m.copy(
        name = m.name.trim().take(MAX_NAME).ifEmpty { "—" },
        tags = normalizeTags(m.tags),
        collection = m.collection?.trim()?.take(MAX_COLLECTION)?.takeIf { it.isNotEmpty() },
        note = m.note.trim().take(MAX_NOTE),
    )

    private fun freshId(index: Index): String {
        val used = (index.moments + index.beforeLoad).mapTo(HashSet()) { it.id }
        while (true) {
            val id = newId()
            checkId(id)
            if (id !in used && Kind.entries.none { ops.exists(stateFile(it, id)) || ops.exists(sramFile(it, id)) }) return id
        }
    }

    private fun stemOf(name: String): String? {
        if (!(name.startsWith(Kind.MOMENT.prefix) || name.startsWith(Kind.BEFORE_LOAD.prefix))) return null
        val dot = name.lastIndexOf('.')
        if (dot < 0) return null
        return when (name.substring(dot)) { ".state", ".sav", ".png" -> name.substring(0, dot) else -> null }
    }

    private fun writeFiles(kind: Kind, id: String, capture: Capture) {
        if (!ops.exists(directory)) ops.mkdirs(directory)
        capture.state?.let { writeAtomic(stateFile(kind, id), it) }
        capture.sram?.let { writeAtomic(sramFile(kind, id), it) }
        capture.thumbnail?.let { try { writeAtomic(thumbnailFile(kind, id), it) } catch (_: IOException) {} }
        ops.syncDirectory(directory)
    }

    private fun deleteFiles(kind: Kind, id: String) {
        for (f in listOf(stateFile(kind, id), sramFile(kind, id), thumbnailFile(kind, id))) {
            try { ops.delete(f) } catch (_: IOException) {}
        }
    }

    private fun writeAtomic(file: File, data: ByteArray) {
        val tmp = File(file.path + ".tmp")
        ops.writeSynced(tmp, data)
        ops.atomicReplace(tmp, file)
    }

    private fun writeIndex(index: Index) {
        if (!ops.exists(directory)) ops.mkdirs(directory)
        writeAtomic(indexFile, json.encodeToString(Index.serializer(), index).toByteArray(Charsets.UTF_8))
        ops.syncDirectory(directory)
    }

    /** `null` si el índice existe pero no se puede interpretar; vacío si no existe. Lanza [IOException] si no se puede leer. */
    private fun readIndex(): Index? {
        if (!ops.exists(indexFile)) return Index()
        val text = ops.readBytes(indexFile, MAX_INDEX_BYTES).toString(Charsets.UTF_8)
        return try {
            val index = json.decodeFromString(Index.serializer(), text)
            // Ids que no son nuestros (editados a mano) no se usan como rutas.
            index.copy(moments = index.moments.filter { ID.matches(it.id) }, beforeLoad = index.beforeLoad.filter { ID.matches(it.id) })
        } catch (_: Exception) {
            null
        }
    }

    /** El índice para modificarlo; si está dañado, lo aparta y lo reconstruye desde los archivos (sin borrar ninguno). */
    private fun indexForWriting(): Index {
        readIndex()?.let { return it }
        val aside = File(directory, "index.damaged-${now()}-${newId().take(6)}.json")
        ops.atomicReplace(indexFile, aside)
        val stems = ops.list(directory).mapNotNull(::stemOf).toSortedSet()
        fun rebuilt(kind: Kind) = stems.filter { it.startsWith(kind.prefix) && ID.matches(it.removePrefix(kind.prefix)) }.map { stem ->
            val id = stem.removePrefix(kind.prefix)
            val date = ops.lastModified(stateFile(kind, id)) ?: ops.lastModified(sramFile(kind, id)) ?: now()
            // N6A-H2: el SHA del estado se recalcula para que una ranura ya migrada no se vuelva a migrar (sin duplicados).
            val sha = try {
                if (ops.exists(stateFile(kind, id))) sha256(ops.readBytes(stateFile(kind, id), StateStore.MAX_STATE_BYTES)) else null
            } catch (_: IOException) {
                null
            }
            Moment(
                id = id, name = "Recuperado $id".take(MAX_NAME), createdMs = date,
                hasState = ops.exists(stateFile(kind, id)), hasSram = ops.exists(sramFile(kind, id)),
                hasThumbnail = ops.exists(thumbnailFile(kind, id)), originSha = if (kind == Kind.MOMENT) sha else null,
            )
        }.filter { it.hasState || it.hasSram }
        // N6A-H2: el anillo reconstruido se queda en las 3 más recientes por fecha de archivo (la más nueva nunca sale).
        val index = Index(moments = rebuilt(Kind.MOMENT), beforeLoad = rebuilt(Kind.BEFORE_LOAD).sortedByDescending { it.createdMs }.take(RING_SIZE))
        writeIndex(index)
        return index
    }
}
