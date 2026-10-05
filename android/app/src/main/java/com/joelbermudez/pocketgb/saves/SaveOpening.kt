package com.joelbermudez.pocketgb.saves

/**
 * Aplica la decisión de carga con E/S real: backups, cuarentena e instalación en local, en ese orden
 * (cada paso solo destruye algo cuando el anterior ya conservó los bytes). Se prueba sobre directorios temporales.
 */
object SaveOpening {
    class Outcome(
        /** Partida a cargar en el core (null = empezar de cero). */
        val data: ByteArray?,
        /** Destino de los guardados (null = esta sesión no guarda). */
        val target: SaveTarget?,
        val warning: SaveLoadWarning?,
    )

    sealed class Refusal : Exception() {
        /**
         * No hay partida local y la del espejo no se pudo leer: abrir empezaría de cero y el primer guardado
         * podría pisarla. Mejor no abrir.
         */
        data object MirrorNotDownloaded : Refusal()
    }

    /** Qué se permite hacer con el espejo en esta sesión. */
    enum class MirrorMode {
        /** Leer y escribir. */
        ReadWrite,

        /** Carpeta de solo lectura (J7): importar el `.sav` si existe, nunca escribirlo, avisar. */
        ReadOnly,

        /** Otra ROM comparte el nombre del `.sav`: el espejo se desactiva por completo (ni leer ni escribir). */
        Shared,
    }

    fun prepare(
        store: SaveStore,
        mirror: SaveMirror?,
        snapshot: SaveMirror.Snapshot,
        validSizes: Set<Int>,
        mirrorMode: MirrorMode = MirrorMode.ReadWrite,
        mirrorWriter: ((ByteArray) -> Long?)? = null,
        registry: MirrorChannelRegistry = MirrorChannelRegistry.shared,
    ): Outcome {
        // Con el espejo desactivado (colisión de nombres) ni siquiera se interpreta su snapshot.
        val activeMirror = if (mirrorMode == MirrorMode.Shared) null else mirror
        // Con solo lectura el espejo se lee, pero el destino no lo recibe jamás.
        val writableMirror = if (mirrorMode == MirrorMode.ReadWrite) activeMirror else null

        fun makeTarget(mirror: SaveMirror?, pending: Boolean = false) =
            SaveTarget(store, mirror, pending, mirrorWriter, registry)

        val local = store.load()?.let { SaveResolution.Candidate(it, store.modificationDateMs) }
        var usableMirror = writableMirror
        var mirrorCandidate: SaveResolution.Candidate? = null
        var unavailable = false
        if (activeMirror != null) {
            when (snapshot) {
                SaveMirror.Snapshot.Absent -> Unit
                is SaveMirror.Snapshot.Read -> mirrorCandidate = SaveResolution.Candidate(snapshot.data, snapshot.dateMs)
                SaveMirror.Snapshot.Unavailable -> {
                    if (local == null) throw Refusal.MirrorNotDownloaded
                    usableMirror = null
                    unavailable = true
                }
            }
        }
        val owned = mirrorCandidate?.let { store.recognizesOwnedMirror(it.data, it.dateMs) } ?: false
        val extraWarning = when (mirrorMode) {
            MirrorMode.ReadOnly -> SaveLoadWarning.MirrorReadOnly
            MirrorMode.Shared -> SaveLoadWarning.MirrorShared
            MirrorMode.ReadWrite -> null
        }
        return when (val r = SaveResolution.resolve(local, mirrorCandidate, owned) { it in validSizes }) {
            SaveResolution.None -> Outcome(null, makeTarget(usableMirror), extraWarning)
            is SaveResolution.Load -> {
                // Orden de efectos: addBackup → cuarentena → instalación. Si uno falla, los siguientes no se hacen.
                r.backupOther?.let(store::addBackup)
                if (r.quarantineLocal) store.quarantineCurrent()
                if (r.installLocal) store.save(r.data)
                val target = makeTarget(if (r.mirrorIgnored) null else usableMirror, pending = r.updateMirror && usableMirror != null)
                val warning = when {
                    r.mirrorIgnored -> SaveLoadWarning.MirrorIgnored
                    r.quarantineLocal -> SaveLoadWarning.LocalQuarantined
                    unavailable -> SaveLoadWarning.MirrorUnavailable
                    else -> extraWarning
                }
                Outcome(r.data, target, warning)
            }
            is SaveResolution.WrongSize ->
                Outcome(null, null, if (r.fromMirror) SaveLoadWarning.MirrorWrongSizeOnly else SaveLoadWarning.LocalWrongSize)
        }
    }
}
