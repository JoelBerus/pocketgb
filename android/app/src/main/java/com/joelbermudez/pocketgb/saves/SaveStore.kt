package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/**
 * Partida local de una ROM: `saves/<huella>.sav` y `saves/backups/<huella>.<n>.sav`. La copia local es la
 * autoritativa (SPEC §5.2); el espejo junto a la ROM es [SaveMirror].
 */
class SaveStore(
    val directory: File,
    val fingerprint: String,
    private val ops: SaveFileOps = PosixSaveFileOps,
) {
    companion object {
        const val KEEP_BACKUPS = 5

        /** Tope de lectura de un `.sav`: la SRAM máxima es 128 KiB (+48); un archivo mayor no es una partida. */
        const val MAX_SAVE_BYTES = 1 shl 20

        private const val HISTORY_LIMIT = 8

        /** Temporal único de un backup: `<huella>.1.<rand8>.tmp` (nunca dos escritores sobre el mismo archivo). */
        fun uniqueBackupTmpName(backupName: String): String =
            backupName.removeSuffix(".sav") + "." + UUID.randomUUID().toString().replace("-", "").take(8) + ".tmp"
    }

    /** La partida local tal y como está en disco (sin lanzar): la apertura decide qué hacer con cada caso. */
    sealed interface LocalSave {
        data object Absent : LocalSave
        class Present(val data: ByteArray) : LocalSave
        /** Supera el tope de lectura: no es una partida (tamaño incorrecto), no se lee entero. */
        data class Oversize(val bytes: Long) : LocalSave
        /** Existe, cabe en el tope, pero no se pudo leer. */
        class Unreadable(val error: IOException) : LocalSave
    }

    /** El backup pedido tiene un tamaño que el cartucho no acepta: restaurarlo sería restaurar basura. */
    class InvalidBackupException(val size: Int, val validSizes: Set<Int>) :
        IOException("La copia de $size bytes no es una partida válida de este juego (válidos: $validSizes)")

    val saveFile: File get() = File(directory, "$fingerprint.sav")
    val mirrorHistoryFile: File get() = File(directory, "$fingerprint.mirror-history.json")
    val backupsDirectory: File get() = File(directory, "backups")
    fun backupFile(n: Int): File = File(backupsDirectory, "$fingerprint.$n.sav")

    private val writer = AtomicSaveWriter(ops, KEEP_BACKUPS)

    /** Lock por partida compartido entre instancias y hilos (ver [SaveLocks]). Toda mutación pasa por él. */
    private val lock: ReentrantLock = SaveLocks.forSave(directory, fingerprint)

    /** Tamaños válidos conocidos (los fija [recoverOrphans] al abrir o [restore]): no se rota a `.1` lo que no lo es. */
    @Volatile private var knownSizes: Set<Int>? = null

    /** `null` si no hay partida. Lanza [IOException] si no se puede leer o supera el tope (no se toca). */
    fun load(): ByteArray? = if (ops.exists(saveFile)) ops.readBytes(saveFile, MAX_SAVE_BYTES) else null

    /** Como [load] pero sin lanzar: distingue ausente, presente, demasiado grande e ilegible. */
    fun inspectLocal(): LocalSave {
        if (!ops.exists(saveFile)) return LocalSave.Absent
        val length = ops.length(saveFile)
        if (length > MAX_SAVE_BYTES) return LocalSave.Oversize(length)
        return try {
            LocalSave.Present(ops.readBytes(saveFile, MAX_SAVE_BYTES))
        } catch (error: IOException) {
            LocalSave.Unreadable(error)
        }
    }

    /** @return `false` si era idéntica a la actual y no se escribió. */
    fun save(data: ByteArray): Boolean = lock.withLock {
        val sizes = knownSizes
        writer.write(data, saveFile, ::backupFile, backupCurrent = { sizes == null || it.size in sizes })
    }

    val modificationDateMs: Long? get() = ops.lastModified(saveFile)

    class BackupInfo(val index: Int, val dateMs: Long?)

    /** Backups existentes (1 = el más reciente), con su fecha. */
    fun backups(): List<BackupInfo> = (1..KEEP_BACKUPS).mapNotNull { n ->
        val f = backupFile(n)
        if (ops.exists(f)) BackupInfo(n, ops.lastModified(f)) else null
    }

    /**
     * Guarda [data] como backup `.1` (rotando los demás) sin tocar la partida actual. Se usa cuando el
     * espejo pierde frente a la local. Si ese contenido ya está entre los backups no se añade otra vez:
     * así un espejo que no se puede actualizar no desplaza el historial en cada apertura.
     */
    fun addBackup(data: ByteArray) = lock.withLock {
        for (n in 1..KEEP_BACKUPS) {
            val f = backupFile(n)
            if (!ops.exists(f)) continue
            val same = try { ops.readBytes(f, MAX_SAVE_BYTES).contentEquals(data) } catch (_: IOException) { false }
            if (same) return@withLock
        }
        if (!ops.exists(backupsDirectory)) ops.mkdirs(backupsDirectory)
        for (n in KEEP_BACKUPS - 1 downTo 1) {
            if (ops.exists(backupFile(n))) ops.atomicReplace(backupFile(n), backupFile(n + 1))
        }
        val tmp = File(backupsDirectory, uniqueBackupTmpName(backupFile(1).name))
        ops.writeSynced(tmp, data)
        ops.atomicReplace(tmp, backupFile(1))
        ops.syncDirectory(backupsDirectory)
    }

    /** Una partida apartada fuera de la rotación ([setAsideMirrorLoser]): nombre del archivo en `backups/` y fecha. */
    class SetAsideInfo(val name: String, val dateMs: Long?)

    private fun isSetAsideName(name: String) = name.startsWith("$fingerprint.mirror-") && name.endsWith(".sav")

    /**
     * N1 · aparta [data] **fuera de la rotación** de backups: `backups/<huella>.mirror-<unix>-<rand8>.sav`. Es el perdedor
     * de una resolución entre la local y un espejo que no es una escritura propia (un duplicado con su propio `.sav`, un
     * `.sav` de otro juego con el mismo nombre…): en la rotación desaparecería tras cinco guardados. Nunca se pisa (nombre
     * único) ni se borra; si ese mismo contenido ya está apartado no se repite. Temporal + `fsync` + rename.
     */
    fun setAsideMirrorLoser(
        data: ByteArray,
        unixSeconds: Long = System.currentTimeMillis() / 1000,
        rand8: () -> String = { UUID.randomUUID().toString().replace("-", "").take(8) },
    ) = lock.withLock {
        for (name in ops.list(backupsDirectory)) {
            if (!isSetAsideName(name)) continue
            val same = try {
                ops.readBytes(File(backupsDirectory, name), MAX_SAVE_BYTES).contentEquals(data)
            } catch (_: IOException) {
                false
            }
            if (same) return@withLock
        }
        if (!ops.exists(backupsDirectory)) ops.mkdirs(backupsDirectory)
        var target: File
        do {
            target = File(backupsDirectory, "$fingerprint.mirror-$unixSeconds-${rand8()}.sav")
        } while (ops.exists(target))
        val tmp = File(target.path + ".tmp")
        ops.writeSynced(tmp, data)
        ops.atomicReplace(tmp, target)
        ops.syncDirectory(backupsDirectory)
    }

    /** Partidas apartadas por [setAsideMirrorLoser], la más reciente primero. */
    fun setAside(): List<SetAsideInfo> =
        ops.list(backupsDirectory).filter(::isSetAsideName)
            .map { SetAsideInfo(it, ops.lastModified(File(backupsDirectory, it))) }
            .sortedWith(compareByDescending<SetAsideInfo> { it.dateMs ?: Long.MIN_VALUE }.thenByDescending { it.name })

    /**
     * Restaura una partida apartada como [restore]: la actual pasa antes a `.1`. La apartada no se borra (sigue
     * restaurable). Rechaza nombres que no sean de esta huella y tamaños que el cartucho no acepta.
     */
    fun restoreSetAside(name: String, validSizes: Set<Int>? = null): Unit = lock.withLock {
        require(isSetAsideName(name) && '/' !in name) { "No es una partida apartada de este juego: $name" }
        val data = ops.readBytes(File(backupsDirectory, name), MAX_SAVE_BYTES)
        if (validSizes != null) {
            if (data.size !in validSizes) throw InvalidBackupException(data.size, validSizes)
            knownSizes = validSizes
        }
        save(data)
        Unit
    }

    /**
     * Aparta la partida actual (tamaño incorrecto) fuera de la rotación de backups:
     * `backups/<huella>.wrong-size-<unix>-<rand8>.sav`. Nunca se rota ni se borra. El sufijo único evita que
     * dos cuarentenas en el mismo segundo se pisen. La partida actual no se modifica (la sustituye el
     * llamador al instalar otra, lo que ya deja la anterior en `.1`).
     */
    fun quarantineCurrent(
        unixSeconds: Long = System.currentTimeMillis() / 1000,
        rand8: String = UUID.randomUUID().toString().replace("-", "").take(8),
    ) = lock.withLock {
        if (!ops.exists(saveFile)) return@withLock
        if (!ops.exists(backupsDirectory)) ops.mkdirs(backupsDirectory)
        val target = File(backupsDirectory, "$fingerprint.wrong-size-$unixSeconds-$rand8.sav")
        val tmp = File(target.path + ".tmp")
        // En streaming: una partida "demasiado grande" no se carga entera en memoria para apartarla.
        ops.copySynced(saveFile, tmp)
        ops.atomicReplace(tmp, target)
        ops.syncDirectory(backupsDirectory)
    }

    /**
     * Restaura el backup [n]: la partida actual pasa antes a ser el backup `.1` (lo hace la escritura
     * atómica), así que restaurar nunca pierde nada. Si se conocen los [validSizes] del cartucho y el backup
     * no es uno de ellos, se rechaza con [InvalidBackupException] sin tocar nada. Una partida actual de tamaño
     * inválido ya está apartada en cuarentena y no se rota a `.1`.
     */
    fun restore(n: Int, validSizes: Set<Int>? = null): Unit = lock.withLock {
        val data = ops.readBytes(backupFile(n), MAX_SAVE_BYTES)
        if (validSizes != null) {
            if (data.size !in validSizes) throw InvalidBackupException(data.size, validSizes)
            knownSizes = validSizes
        }
        save(data)
        Unit
    }

    /**
     * Paso 6 de SPEC §5.2: un `.sav.tmp` huérfano se instala si no hay `.sav` y su tamaño es uno de
     * [validSizes]; si no, se borra. Se borran también todos los temporales huérfanos de esta partida:
     * `.1.tmp` (formato antiguo) y `.1.<rand>.tmp` de los backups, `wrong-size-*.sav.tmp` de la cuarentena,
     * `mirror-*.sav.tmp` de las apartadas (N1) y el temporal del historial. Fija [validSizes] como los tamaños conocidos de esta partida.
     */
    fun recoverOrphans(validSizes: Set<Int>) = lock.withLock {
        knownSizes = validSizes
        val tmp = File(saveFile.path + ".tmp")
        if (ops.exists(tmp)) {
            val size = ops.length(tmp)
            if (!ops.exists(saveFile) && size in validSizes.map { it.toLong() }) {
                ops.atomicReplace(tmp, saveFile)
                ops.syncDirectory(directory)
            } else {
                ops.delete(tmp)
            }
        }
        for (name in ops.list(backupsDirectory)) {
            val orphanBackup = name.startsWith("$fingerprint.1.") && name.endsWith(".tmp")
            val orphanQuarantine = name.startsWith("$fingerprint.wrong-size-") && name.endsWith(".sav.tmp")
            val orphanSetAside = name.startsWith("$fingerprint.mirror-") && name.endsWith(".sav.tmp")
            if (orphanBackup || orphanQuarantine || orphanSetAside) ops.delete(File(backupsDirectory, name))
        }
        ops.delete(File(mirrorHistoryFile.path + ".tmp"))
    }

    // MARK: historial del espejo

    /** Escritura confirmada: contenido (hash) y fecha del espejo observada justo tras escribirlo. */
    private class Written(val hash: String, val dateMs: Long?)

    private class MirrorHistory(
        val successful: List<Written>,
        val pending: List<String>,
        val received: List<String> = emptyList(),
        /** ND20 (m): ubicación del espejo de la última escritura confirmada. */
        val location: String? = null,
    )

    /**
     * ¿Es este espejo (contenido + fecha) una escritura de PocketGB?
     * - Sí, si coincide con una escritura confirmada y su fecha es la observada al escribirla: nadie lo
     *   tocó después, así que una fecha más nueva que la local solo significa que una escritura vieja terminó tarde.
     * - Sí, si es la última escritura sin confirmar (write-ahead: el proceso pudo morir a mitad).
     * - No en cualquier otro caso: un contenido antiguo restaurado a mano tiene fecha nueva y se resuelve
     *   por fecha, con backup del perdedor.
     */
    fun recognizesOwnedMirror(data: ByteArray, dateMs: Long?): Boolean {
        val hash = contentHash(data)
        val history = readHistory()
        if (history.pending.firstOrNull() == hash) return true
        if (dateMs == null) return false
        return history.successful.any { it.hash == hash && it.dateMs == dateMs }
    }

    /** Write-ahead: se anota ANTES de escribir el espejo. */
    fun recordMirrorAttempt(data: ByteArray) = lock.withLock {
        val hash = contentHash(data)
        val h = readHistory()
        writeHistory(MirrorHistory(h.successful, (listOf(hash) + h.pending.filter { it != hash }).take(HISTORY_LIMIT), h.received, h.location))
    }

    /** @param observedDateMs fecha de modificación del espejo leída justo después de escribirlo. */
    fun recordSuccessfulMirror(data: ByteArray, observedDateMs: Long?, location: String? = null) = lock.withLock {
        val hash = contentHash(data)
        val h = readHistory()
        writeHistory(
            MirrorHistory(
                (listOf(Written(hash, observedDateMs)) + h.successful.filter { it.hash != hash }).take(HISTORY_LIMIT),
                h.pending.filter { it != hash },
                h.received,
                location ?: h.location,
            ),
        )
    }

    internal fun mirrorHistoryPending(): List<String> = readHistory().pending

    // MARK: N7a · linaje (N-README §3.4)

    /**
     * Huellas que cuentan como historial propio del linaje: escrituras del espejo (write-ahead y confirmadas) y, ND20 (b′)
     * como iOS, las partidas recibidas de fuera e instaladas (importación, cambio externo). Así un espejo que es una
     * recibida anterior es OWN_OLDER (gana la local) y un historial con solo recibidas decide por linaje, no por fecha.
     * Cada lista va acotada a las últimas [HISTORY_LIMIT].
     */
    fun ownMirrorHashes(): List<String> =
        readHistory().let { (it.pending + it.successful.map { w -> w.hash } + it.received).distinct() }

    /**
     * La última partida «nuestra»: la última escritura intentada, la última confirmada y (ND20 b, H1) la última recibida de
     * fuera. Si la local es una de ellas, no cambió desde entonces: abrir y cerrar sin jugar no crea una divergencia.
     */
    fun lastOwnMirrorHashes(): Set<String> =
        readHistory().let { setOfNotNull(it.pending.firstOrNull(), it.successful.firstOrNull()?.hash, it.received.firstOrNull()) }

    /** ND20 (m): ubicación del espejo de la última escritura confirmada (`null` = historial antiguo o sin escrituras). */
    fun mirrorLocation(): String? = readHistory().location

    /**
     * ND20 (d): huellas que este equipo ya tuvo: historial de escrituras, recibidas, copias de seguridad y apartadas. Un
     * paquete o `.sav` con una de ellas «ya se conoce» y se pregunta antes de instalarlo.
     */
    fun knownHashes(): Set<String> {
        val h = readHistory()
        val files = backups().map { backupFile(it.index) } + setAside().map { File(backupsDirectory, it.name) }
        val stored = files.mapNotNull { f -> try { contentHash(ops.readBytes(f, MAX_SAVE_BYTES)) } catch (_: IOException) { null } }
        return (h.pending + h.successful.map { it.hash } + h.received + stored).toSet()
    }

    /**
     * `base_sav_sha256` de un paquete que sale de aquí: la última partida que este equipo instaló o recibió de fuera
     * (cambio externo del espejo, importación). `null` si nunca recibió ninguna.
     */
    fun lineageBase(): String? = readHistory().received.firstOrNull()

    /** Anota que [data] llegó de fuera y se instaló (o se conservó como base): entra en el linaje. */
    fun recordReceived(data: ByteArray) = recordReceivedHash(contentHash(data))

    fun recordReceivedHash(hash: String) = lock.withLock {
        val h = readHistory()
        // H3: la ubicación del espejo (ND20 m) se conserva; perderla desactivaba la comprobación de duplicados.
        writeHistory(MirrorHistory(h.successful, h.pending, (listOf(hash) + h.received.filter { it != hash }).take(HISTORY_LIMIT), h.location))
    }

    // MARK: N7b/N7c · origen de la partida («Partida: iPhone · hace 2 h»)

    val originFile: File get() = File(directory, "$fingerprint.origin.json")

    /**
     * De qué equipo llegó la partida [hash] y cuándo la escribió (`created_ms` del paquete). [stateHash]: SHA-256 del estado
     * automático que llegó con ella y se instaló (para «Continuar donde lo dejaste en <equipo>» mientras siga siendo el
     * automático; `null` = no llegó ninguno).
     */
    data class Origin(val hash: String, val platform: String, val deviceName: String, val createdMs: Long, val stateHash: String? = null)

    fun recordOrigin(origin: Origin) = lock.withLock {
        val json = buildJsonObject {
            put("hash", origin.hash)
            put("platform", origin.platform)
            put("name", origin.deviceName)
            put("created", origin.createdMs)
            origin.stateHash?.let { put("state", it) }
        }
        if (!ops.exists(directory)) ops.mkdirs(directory)
        val tmp = File(originFile.path + ".tmp")
        ops.writeSynced(tmp, json.toString().toByteArray(Charsets.UTF_8))
        ops.atomicReplace(tmp, originFile)
    }

    /** El origen anotado, solo si sigue siendo la partida actual [currentHash] (si se jugó aquí después, ya no vale). */
    fun origin(currentHash: String?): Origin? = try {
        if (currentHash == null || !ops.exists(originFile)) null
        else {
            val o = Json.parseToJsonElement(ops.readBytes(originFile, 1 shl 12).toString(Charsets.UTF_8)).jsonObject
            val hash = (o["hash"] as? JsonPrimitive)?.contentOrNull
            val origin = Origin(
                hash ?: "", (o["platform"] as? JsonPrimitive)?.contentOrNull ?: "",
                (o["name"] as? JsonPrimitive)?.contentOrNull ?: "", (o["created"] as? JsonPrimitive)?.longOrNull ?: 0L,
                (o["state"] as? JsonPrimitive)?.contentOrNull,
            )
            origin.takeIf { hash == currentHash && it.deviceName.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    // MARK: N7a · copias en conflicto del proveedor

    val providerConflictsFile: File get() = File(directory, "$fingerprint.provider-conflicts.json")

    /** Una copia en conflicto del proveedor junto al juego (`X 2.sav`…): solo se lista; nunca se borra ni se lee. */
    class ProviderConflict(val name: String, val dateMs: Long?)

    /** Sustituye la lista vista en la última apertura (vacía = ya no hay). Mejor esfuerzo: un fallo no impide jugar. */
    fun recordProviderConflicts(found: List<ProviderConflict>) = lock.withLock {
        if (found.isEmpty() && !ops.exists(providerConflictsFile)) return@withLock
        val json = buildJsonArray {
            for (c in found.take(32)) add(buildJsonObject {
                put("name", c.name)
                if (c.dateMs != null) put("date", c.dateMs) else put("date", JsonNull)
            })
        }
        if (!ops.exists(directory)) ops.mkdirs(directory)
        val tmp = File(providerConflictsFile.path + ".tmp")
        ops.writeSynced(tmp, json.toString().toByteArray(Charsets.UTF_8))
        ops.atomicReplace(tmp, providerConflictsFile)
    }

    fun providerConflicts(): List<ProviderConflict> = try {
        if (!ops.exists(providerConflictsFile)) emptyList()
        else (Json.parseToJsonElement(ops.readBytes(providerConflictsFile, 1 shl 16).toString(Charsets.UTF_8)) as? JsonArray).orEmpty()
            .mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                ProviderConflict(name, (o["date"] as? JsonPrimitive)?.longOrNull)
            }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeHistory(history: MirrorHistory) {
        val json = buildJsonObject {
            put("successful", buildJsonArray {
                for (w in history.successful) add(buildJsonObject {
                    put("hash", w.hash)
                    if (w.dateMs != null) put("date", w.dateMs) else put("date", JsonNull)
                })
            })
            put("pending", buildJsonArray { history.pending.forEach { add(JsonPrimitive(it)) } })
            put("received", buildJsonArray { history.received.forEach { add(JsonPrimitive(it)) } })
            if (history.location != null) put("location", history.location)
        }
        if (!ops.exists(directory)) ops.mkdirs(directory)
        val tmp = File(mirrorHistoryFile.path + ".tmp")
        ops.writeSynced(tmp, json.toString().toByteArray(Charsets.UTF_8))
        ops.atomicReplace(tmp, mirrorHistoryFile)
        ops.syncDirectory(directory)
    }

    /** Tolerante: ausente, corrupto o con formato antiguo (solo huellas, sin fecha) nunca prueba nada. */
    private fun readHistory(): MirrorHistory = try {
        if (!ops.exists(mirrorHistoryFile)) MirrorHistory(emptyList(), emptyList())
        else {
            val root = Json.parseToJsonElement(ops.readBytes(mirrorHistoryFile, 1 shl 16).toString(Charsets.UTF_8)).jsonObject
            val successful = (root["successful"] as? JsonArray).orEmpty().mapNotNull { e ->
                when (e) {
                    is JsonObject -> (e["hash"] as? JsonPrimitive)?.contentOrNull
                        ?.let { Written(it, (e["date"] as? JsonPrimitive)?.longOrNull) }
                    is JsonPrimitive -> e.contentOrNull?.let { Written(it, null) }
                    else -> null
                }
            }
            val pending = (root["pending"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val received = (root["received"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            MirrorHistory(successful, pending, received, (root["location"] as? JsonPrimitive)?.contentOrNull)
        }
    } catch (_: Exception) {
        MirrorHistory(emptyList(), emptyList())
    }

    fun contentHash(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
