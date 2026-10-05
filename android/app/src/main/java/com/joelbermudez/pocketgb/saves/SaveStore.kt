package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
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
    }

    val saveFile: File get() = File(directory, "$fingerprint.sav")
    val mirrorHistoryFile: File get() = File(directory, "$fingerprint.mirror-history.json")
    val backupsDirectory: File get() = File(directory, "backups")
    fun backupFile(n: Int): File = File(backupsDirectory, "$fingerprint.$n.sav")

    private val writer = AtomicSaveWriter(ops, KEEP_BACKUPS)

    /** `null` si no hay partida. Lanza [IOException] si no se puede leer o supera el tope (no se toca). */
    fun load(): ByteArray? = if (ops.exists(saveFile)) ops.readBytes(saveFile, MAX_SAVE_BYTES) else null

    /** @return `false` si era idéntica a la actual y no se escribió. */
    fun save(data: ByteArray): Boolean = writer.write(data, saveFile, ::backupFile)

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
    fun addBackup(data: ByteArray) {
        for (n in 1..KEEP_BACKUPS) {
            val f = backupFile(n)
            if (!ops.exists(f)) continue
            val same = try { ops.readBytes(f, MAX_SAVE_BYTES).contentEquals(data) } catch (_: IOException) { false }
            if (same) return
        }
        if (!ops.exists(backupsDirectory)) ops.mkdirs(backupsDirectory)
        for (n in KEEP_BACKUPS - 1 downTo 1) {
            if (ops.exists(backupFile(n))) ops.atomicReplace(backupFile(n), backupFile(n + 1))
        }
        val tmp = File(backupsDirectory, "$fingerprint.1.tmp")
        ops.writeSynced(tmp, data)
        ops.atomicReplace(tmp, backupFile(1))
        ops.syncDirectory(backupsDirectory)
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
    ) {
        val data = load() ?: return
        if (!ops.exists(backupsDirectory)) ops.mkdirs(backupsDirectory)
        val target = File(backupsDirectory, "$fingerprint.wrong-size-$unixSeconds-$rand8.sav")
        val tmp = File(target.path + ".tmp")
        ops.writeSynced(tmp, data)
        ops.atomicReplace(tmp, target)
        ops.syncDirectory(backupsDirectory)
    }

    /**
     * Restaura el backup [n]: la partida actual pasa antes a ser el backup `.1` (lo hace la escritura
     * atómica), así que restaurar nunca pierde nada.
     */
    fun restore(n: Int) {
        val data = ops.readBytes(backupFile(n), MAX_SAVE_BYTES)
        save(data)
    }

    /**
     * Paso 6 de SPEC §5.2: un `.sav.tmp` huérfano se instala si no hay `.sav` y su tamaño es uno de
     * [validSizes]; si no, se borra. Los `.1.tmp` y el temporal del historial se borran.
     */
    fun recoverOrphans(validSizes: Set<Int>) {
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
        ops.delete(File(backupsDirectory, "$fingerprint.1.tmp"))
        ops.delete(File(mirrorHistoryFile.path + ".tmp"))
    }

    // MARK: historial del espejo

    /** Escritura confirmada: contenido (hash) y fecha del espejo observada justo tras escribirlo. */
    private class Written(val hash: String, val dateMs: Long?)

    private class MirrorHistory(val successful: List<Written>, val pending: List<String>)

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
    fun recordMirrorAttempt(data: ByteArray) {
        val hash = contentHash(data)
        val h = readHistory()
        writeHistory(MirrorHistory(h.successful, (listOf(hash) + h.pending.filter { it != hash }).take(HISTORY_LIMIT)))
    }

    /** @param observedDateMs fecha de modificación del espejo leída justo después de escribirlo. */
    fun recordSuccessfulMirror(data: ByteArray, observedDateMs: Long?) {
        val hash = contentHash(data)
        val h = readHistory()
        writeHistory(
            MirrorHistory(
                (listOf(Written(hash, observedDateMs)) + h.successful.filter { it.hash != hash }).take(HISTORY_LIMIT),
                h.pending.filter { it != hash },
            ),
        )
    }

    internal fun mirrorHistoryPending(): List<String> = readHistory().pending

    private fun writeHistory(history: MirrorHistory) {
        val json = buildJsonObject {
            put("successful", buildJsonArray {
                for (w in history.successful) add(buildJsonObject {
                    put("hash", w.hash)
                    if (w.dateMs != null) put("date", w.dateMs) else put("date", JsonNull)
                })
            })
            put("pending", buildJsonArray { history.pending.forEach { add(JsonPrimitive(it)) } })
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
            MirrorHistory(successful, pending)
        }
    } catch (_: Exception) {
        MirrorHistory(emptyList(), emptyList())
    }

    private fun contentHash(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
