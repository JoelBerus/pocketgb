package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException

/**
 * N6 · momentos de un juego vistos desde el detalle, sin sesión abierta. Lo que toca la partida o las ranuras antiguas
 * (migrar, recuperar la RAM de un momento) va bajo la propiedad exclusiva de la huella ([FingerprintOwnership]): solo
 * ocurre sin sesión abierta o aparcada de esa huella (§3.3). Editar o borrar un momento no toca la partida y solo usa el
 * lock del propio almacén.
 */
class MomentLibrary(
    private val momentsRoot: File,
    private val statesRoot: File,
    private val savesDirectory: File,
    private val ops: SaveFileOps = PosixSaveFileOps,
    private val ownership: FingerprintOwnership = FingerprintOwnership.shared,
    private val migratedName: (String) -> String = { it },
) {
    /** El momento no guardó la RAM del cartucho (p. ej. una ranura migrada). */
    class NoSramException : IOException("Este momento no guardó la partida")

    fun store(fingerprint: String): MomentStore {
        require(FINGERPRINT.matches(fingerprint)) { "Huella no válida" }
        return MomentStore(momentsRoot, fingerprint, ops)
    }

    /**
     * Los momentos de [fingerprint]. Si la huella está libre, antes recoge temporales y migra las ranuras 1–4 y RESCUE
     * (con el lease); si la tiene una sesión, solo lee.
     */
    fun snapshot(fingerprint: String): MomentStore.Snapshot {
        val store = store(fingerprint)
        ownership.tryAcquire(fingerprint, "momentos")?.use {
            try {
                store.recoverOrphans()
                store.migrateSlots(StateStore(statesRoot, fingerprint, ops), migratedName)
            } catch (_: IOException) {
            }
        }
        return store.snapshot()
    }

    fun thumbnail(fingerprint: String, kind: MomentStore.Kind, id: String): ByteArray? = store(fingerprint).thumbnail(kind, id)

    fun update(fingerprint: String, id: String, name: String, tags: List<String>, collection: String?, note: String) =
        store(fingerprint).update(id, name, tags, collection, note)

    fun delete(fingerprint: String, kind: MomentStore.Kind, id: String) = store(fingerprint).delete(kind, id)

    /**
     * «Recuperar» la partida de un momento o de una entrada del anillo (ND13): instala su RAM del cartucho como `.sav`.
     * Antes, la partida actual entra en el anillo «Antes de cargar» y, como toda escritura de la partida, pasa además al
     * backup `.1`: nunca se pierde. Rechaza tamaños que el cartucho no acepta. Lanza [SavePendingException] si la huella
     * tiene dueño (sesión abierta o aparcada, restauración…).
     */
    fun installSram(fingerprint: String, kind: MomentStore.Kind, id: String, label: String, openFingerprint: String?) {
        check(fingerprint != openFingerprint) { "No se puede recuperar mientras el juego está abierto" }
        ownership.withExclusive(fingerprint, "momento") {
            val moments = store(fingerprint)
            val sram = moments.loadSram(kind, id) ?: throw NoSramException()
            val sizes = SavesIndex(savesDirectory, ops).load()[fingerprint]?.validSizes?.toSet()
            if (sizes != null && sram.size !in sizes) throw SaveStore.InvalidBackupException(sram.size, sizes)
            val save = SaveStore(savesDirectory, fingerprint, ops)
            val current = save.load()
            // N6A-H2: la entrada recuperada no sale del anillo y las expulsadas solo se borran tras confirmar la partida.
            val pending = if (current != null && !current.contentEquals(sram)) {
                moments.pushBeforeLoadDeferred(
                    MomentStore.Capture(null, current, null), label, protect = id.takeIf { kind == MomentStore.Kind.BEFORE_LOAD },
                )
            } else {
                null
            }
            save.save(sram)
            pending?.commit()
        }
    }

    private companion object {
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}
