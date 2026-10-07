package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Qué juego corresponde a cada huella de `saves/` (`saves/index.json`), para que Ajustes › Partidas muestre
 * títulos y no hashes. Solo metadatos: si se pierde, las partidas siguen intactas y se listan por huella.
 */
class SavesIndex(private val directory: File, private val ops: SaveFileOps = PosixSaveFileOps) {
    @Serializable
    data class Record(
        val title: String,
        val fileName: String,
        /** Tamaños de `.sav` válidos del cartucho (para validar una restauración); `null` en entradas antiguas. */
        val validSizes: List<Int>? = null,
    )

    class SavedGame(val fingerprint: String, val record: Record?)

    private val file get() = File(directory, "index.json")
    private val serializer = MapSerializer(String.serializer(), Record.serializer())

    fun load(): Map<String, Record> = try {
        if (!ops.exists(file)) emptyMap()
        else Json.decodeFromString(serializer, ops.readBytes(file, 1 shl 20).toString(Charsets.UTF_8))
    } catch (_: Exception) {
        emptyMap()
    }

    /** Mejor esfuerzo: un fallo aquí nunca afecta a las partidas. */
    fun record(fingerprint: String, title: String, fileName: String, validSizes: Set<Int>? = null) {
        val records = load().toMutableMap()
        val new = Record(title, fileName, validSizes?.sorted())
        if (records[fingerprint] == new) return
        records[fingerprint] = new
        try {
            if (!ops.exists(directory)) ops.mkdirs(directory)
            val tmp = File(file.path + ".tmp")
            ops.writeSynced(tmp, Json.encodeToString(serializer, records).toByteArray(Charsets.UTF_8))
            ops.atomicReplace(tmp, file)
        } catch (_: IOException) {
        }
    }

    /** Borra el temporal huérfano del índice (`index.json.tmp`) de una escritura interrumpida. */
    fun recoverOrphans() {
        try { ops.delete(File(file.path + ".tmp")) } catch (_: IOException) {}
    }

    /** Huellas con partida local (`<huella>.sav`), con su título si se conoce, ordenadas por título. */
    fun savedGames(): List<SavedGame> {
        val records = load()
        return ops.list(directory).filter { it.endsWith(".sav") }
            .map { it.removeSuffix(".sav") }
            .map { SavedGame(it, records[it]) }
            .sortedBy { it.record?.title ?: it.fingerprint }
    }
}
