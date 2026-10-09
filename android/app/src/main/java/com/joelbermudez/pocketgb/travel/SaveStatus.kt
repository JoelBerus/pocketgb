package com.joelbermudez.pocketgb.travel

import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.saves.PosixSaveFileOps
import com.joelbermudez.pocketgb.saves.SaveFileOps
import com.joelbermudez.pocketgb.saves.SaveStore
import java.io.File
import java.io.IOException

/**
 * N7c · de dónde viene la partida actual, para el detalle: «Partida: iPhone · hace 2 h». [device] `null` = se jugó por
 * última vez en este teléfono (o llegó sin paquete). [atMs]: cuándo la escribió el otro equipo (`created_ms`) o la fecha
 * del `.sav` local.
 */
data class SaveStatus(val device: String?, val platform: String?, val atMs: Long) {
    companion object {
        fun read(savesDirectory: File, fingerprint: String, ops: SaveFileOps = PosixSaveFileOps): SaveStatus? {
            val store = SaveStore(savesDirectory, fingerprint, ops)
            val data = try { store.load() } catch (_: IOException) { null } ?: return null
            store.origin(MomentStore.sha256(data))?.let { return SaveStatus(it.deviceName, it.platform, it.createdMs) }
            return store.modificationDateMs?.let { SaveStatus(null, "android", it) }
        }
    }
}
