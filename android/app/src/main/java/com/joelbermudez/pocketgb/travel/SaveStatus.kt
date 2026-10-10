package com.joelbermudez.pocketgb.travel

import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.saves.PosixSaveFileOps
import com.joelbermudez.pocketgb.saves.SaveFileOps
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import java.io.File
import java.io.IOException

/**
 * N7c · de dónde viene la partida actual, para el detalle: «Partida: iPhone · hace 2 h». [device] `null` = se jugó por
 * última vez en este teléfono (o llegó sin paquete). [atMs]: cuándo la escribió el otro equipo (`created_ms`) o la fecha
 * del `.sav` local. [continueFrom]: equipo del que llegó el estado automático actual con la partida (= iOS
 * `continuesFromOtherDevice`): el detalle dice «Continuar donde lo dejaste en <equipo>». Deja de valer en cuanto se juega
 * aquí (otra partida u otro estado automático).
 */
data class SaveStatus(val device: String?, val platform: String?, val atMs: Long, val continueFrom: String? = null) {
    companion object {
        fun read(savesDirectory: File, fingerprint: String, ops: SaveFileOps = PosixSaveFileOps, statesRoot: File? = null): SaveStatus? {
            val store = SaveStore(savesDirectory, fingerprint, ops)
            val data = try { store.load() } catch (_: IOException) { null } ?: return null
            store.origin(MomentStore.sha256(data))?.let { o ->
                return SaveStatus(o.deviceName, o.platform, o.createdMs, o.deviceName.takeIf { continuesFromPackage(o, statesRoot, fingerprint, ops) })
            }
            return store.modificationDateMs?.let { SaveStatus(null, "android", it) }
        }

        /** ¿El estado automático de aquí es el que llegó con la partida? */
        private fun continuesFromPackage(o: SaveStore.Origin, statesRoot: File?, fingerprint: String, ops: SaveFileOps): Boolean {
            val expected = o.stateHash ?: return false
            val root = statesRoot ?: return false
            val auto = try { StateStore(root, fingerprint, ops).load(StateSlot.AUTO) } catch (_: IOException) { return false }
            return MomentStore.sha256(auto) == expected
        }
    }
}
