package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException

/**
 * Ranura de save state (SPEC §5.4): una automática, cuatro manuales y la de rescate. [RESCUE] solo la escribe la
 * salida "sin guardar" tras un fallo local (J6): nunca la pisa un guardado normal y [StateStore.saveRescue] aparta
 * la anterior en vez de sobrescribirla.
 */
enum class StateSlot(val fileStem: String) {
    AUTO("auto"), MANUAL1("slot1"), MANUAL2("slot2"), MANUAL3("slot3"), MANUAL4("slot4"), RESCUE("rescue");

    companion object {
        val MANUAL: List<StateSlot> = listOf(MANUAL1, MANUAL2, MANUAL3, MANUAL4)
    }
}

/**
 * Save states de un juego en `states/<huella>/`: `<ranura>.state` escrito de forma atómica (tmp + fsync +
 * rename + fsync del directorio) y su captura `<ranura>.png` (mejor esfuerzo; son bytes opacos: la
 * codificación PNG la hace la capa Android). Los estados son independientes de la partida (SRAM).
 */
class StateStore(val directory: File, private val ops: SaveFileOps = PosixSaveFileOps) {
    constructor(root: File, fingerprint: String, ops: SaveFileOps = PosixSaveFileOps) :
        this(File(root, fingerprint), ops)

    companion object {
        /** Tope de lectura de un estado (entrada no confiable): el real ronda las centenas de KiB. */
        const val MAX_STATE_BYTES = 4 shl 20
        const val MAX_THUMBNAIL_BYTES = 1 shl 20
        private val MAGIC = "PGBS".toByteArray(Charsets.US_ASCII)
    }

    class Entry(val slot: StateSlot, val dateMs: Long, val thumbnail: ByteArray?, val corrupt: Boolean) {
        override fun equals(other: Any?): Boolean {
            if (other !is Entry || slot != other.slot || dateMs != other.dateMs || corrupt != other.corrupt) return false
            return java.util.Arrays.equals(thumbnail, other.thumbnail)
        }
        override fun hashCode() = 31 * (31 * slot.hashCode() + dateMs.hashCode()) + (thumbnail?.contentHashCode() ?: 0)
    }

    fun stateFile(slot: StateSlot) = File(directory, "${slot.fileStem}.state")
    fun thumbnailFile(slot: StateSlot) = File(directory, "${slot.fileStem}.png")

    /** Ranuras ocupadas. `corrupt` si la firma no es `PGBS` (solo se lee la cabecera). */
    fun entries(): Map<StateSlot, Entry> {
        val result = linkedMapOf<StateSlot, Entry>()
        for (slot in StateSlot.entries) {
            val file = stateFile(slot)
            if (!ops.exists(file)) continue
            val head = try { ops.readPrefix(file, MAGIC.size) } catch (_: IOException) { ByteArray(0) }
            val thumb = try {
                if (ops.exists(thumbnailFile(slot))) ops.readBytes(thumbnailFile(slot), MAX_THUMBNAIL_BYTES) else null
            } catch (_: IOException) {
                null
            }
            result[slot] = Entry(slot, ops.lastModified(file) ?: 0L, thumb, !head.contentEquals(MAGIC))
        }
        return result
    }

    /** Escribe el estado (atómico) y después su captura. Si la captura falla, el estado vale igual. */
    fun save(state: ByteArray, thumbnail: ByteArray?, slot: StateSlot) {
        if (!ops.exists(directory)) ops.mkdirs(directory)
        replace(stateFile(slot), state)
        if (thumbnail != null) {
            try { replace(thumbnailFile(slot), thumbnail) } catch (_: IOException) {}
        } else {
            try { ops.delete(thumbnailFile(slot)) } catch (_: IOException) {}
        }
    }

    /** ¿Hay un estado de rescate de una salida con fallo de guardado? (se avisa al abrir el juego). */
    fun hasRescue(): Boolean = ops.exists(stateFile(StateSlot.RESCUE))

    /**
     * Guarda el estado de rescate (J6) sin pisar uno anterior: si ya existía, se aparta como
     * `rescue-<fecha>-<rand>.state` (y su captura) y no se borra jamás.
     */
    fun saveRescue(state: ByteArray, thumbnail: ByteArray?) {
        if (!ops.exists(directory)) ops.mkdirs(directory)
        val current = stateFile(StateSlot.RESCUE)
        if (ops.exists(current)) {
            val stamp = "${ops.lastModified(current) ?: System.currentTimeMillis()}-" +
                java.util.UUID.randomUUID().toString().replace("-", "").take(6)
            ops.atomicReplace(current, File(directory, "rescue-$stamp.state"))
            if (ops.exists(thumbnailFile(StateSlot.RESCUE))) {
                try { ops.atomicReplace(thumbnailFile(StateSlot.RESCUE), File(directory, "rescue-$stamp.png")) } catch (_: IOException) {}
            }
        }
        save(state, thumbnail, StateSlot.RESCUE)
    }

    /** Borra los temporales huérfanos (`*.state.tmp`, `*.png.tmp`) de una escritura interrumpida. */
    fun recoverOrphans() {
        for (name in ops.list(directory)) {
            if (name.endsWith(".state.tmp") || name.endsWith(".png.tmp")) ops.delete(File(directory, name))
        }
    }

    /** Lanza [IOException] si no existe o supera [MAX_STATE_BYTES]. */
    fun load(slot: StateSlot): ByteArray = ops.readBytes(stateFile(slot), MAX_STATE_BYTES)

    fun delete(slot: StateSlot) {
        ops.delete(stateFile(slot))
        try { ops.delete(thumbnailFile(slot)) } catch (_: IOException) {}
    }

    private fun replace(file: File, data: ByteArray) {
        val tmp = File(file.path + ".tmp")
        ops.writeSynced(tmp, data)
        ops.atomicReplace(tmp, file)
        ops.syncDirectory(directory)
    }
}
