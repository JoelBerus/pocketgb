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

    /**
     * Snapshot del espejo SOLO cuando el canal de esa huella no tiene una escritura en vuelo, esperando como
     * mucho [waitMs]. La lectura ocurre DENTRO de un lease del canal ([MirrorChannel.withIdleLease]): comprobar
     * "en reposo" y leer es una sola operación, así que ningún `enqueue` puede iniciar un `wt` (que trunca primero
     * y dejaría un `.sav` parcial; con RTC un prefijo válido se importaría sin reloj) entre ambas cosas. Si el
     * canal no se vacía a tiempo el espejo es [SaveMirror.Snapshot.Unavailable]: nunca se lee parcial. Se llama
     * fuera del hilo principal.
     */
    fun snapshotWhenIdle(mirror: SaveMirror, channel: MirrorChannel, waitMs: Long = 5_000): SaveMirror.Snapshot =
        channel.withIdleLease(waitMs) { mirror.snapshot() } ?: SaveMirror.Snapshot.Unavailable

    fun prepare(
        store: SaveStore,
        mirror: SaveMirror?,
        snapshot: SaveMirror.Snapshot,
        validSizes: Set<Int>,
        mirrorMode: MirrorMode = MirrorMode.ReadWrite,
        mirrorWriter: ((ByteArray) -> Long?)? = null,
        registry: MirrorChannelRegistry = MirrorChannelRegistry.shared,
        /** N7a: guarda la partida del otro lado de una divergencia como momento «Conflicto …»; devuelve su id. */
        recordConflict: ((ByteArray) -> String?)? = null,
    ): Outcome {
        // Con el espejo desactivado (colisión de nombres) ni siquiera se interpreta su snapshot.
        val activeMirror = if (mirrorMode == MirrorMode.Shared) null else mirror
        // Con solo lectura el espejo se lee, pero el destino no lo recibe jamás.
        val writableMirror = if (mirrorMode == MirrorMode.ReadWrite) activeMirror else null

        fun makeTarget(mirror: SaveMirror?, pending: Boolean = false) =
            SaveTarget(store, mirror, pending, mirrorWriter, registry)

        // Una local demasiado grande (> tope de lectura) o ilegible NO falla la apertura para siempre: cuenta como
        // "tamaño incorrecto" (candidata vacía, nunca válida) y se aparta en streaming si hay que sustituirla.
        val localState = store.inspectLocal()
        val local = when (localState) {
            SaveStore.LocalSave.Absent -> null
            is SaveStore.LocalSave.Present -> SaveResolution.Candidate(localState.data, store.modificationDateMs)
            is SaveStore.LocalSave.Oversize, is SaveStore.LocalSave.Unreadable ->
                SaveResolution.Candidate(ByteArray(0), store.modificationDateMs)
        }
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
        // N7a · linaje (§3.4): con local y espejo válidos y distintos decide la tabla por huellas, no por fechas.
        val localValid = (localState as? SaveStore.LocalSave.Present)?.data?.takeIf { it.size in validSizes }
        val mirrorValid = mirrorCandidate?.data?.takeIf { it.size in validSizes }
        val lineage = if (localValid != null && mirrorValid != null) {
            SaveLineage.classifyMirror(
                store.contentHash(localValid), store.contentHash(mirrorValid), store.ownMirrorHashes(), store.lastOwnMirrorHashes(),
            )
        } else {
            null
        }
        val owned = lineage == SaveLineage.Mirror.OWN_OLDER
        val extraWarning = when (mirrorMode) {
            MirrorMode.ReadOnly -> SaveLoadWarning.MirrorReadOnly
            MirrorMode.Shared -> SaveLoadWarning.MirrorShared
            MirrorMode.ReadWrite -> null
        }
        if (lineage == SaveLineage.Mirror.EXTERNAL_CHANGE && localValid != null && mirrorValid != null) {
            // Cambio externo: la local no cambió desde nuestra última escritura. Se instala con backup (la escritura
            // atómica deja la local en `.1`) y aviso, sea cual sea la fecha (reloj desfasado).
            // Además la local se aparta fuera de la rotación (N1): el historial es por huella, no por espejo, así que
            // un duplicado con su propio `.sav` también cae aquí, y cinco guardados no deben poder borrarla.
            store.setAsideMirrorLoser(localValid)
            store.save(mirrorValid)
            store.recordReceived(mirrorValid)
            return Outcome(mirrorValid, makeTarget(usableMirror), SaveLoadWarning.ExternalChange)
        }
        if (lineage == SaveLineage.Mirror.DIVERGENCE && localValid != null && mirrorValid != null) {
            // Divergencia: las dos cambiaron por separado. Sigue la local; la otra se aparta (fuera de la rotación), va
            // a backup y queda como momento «Conflicto …» antes de reescribir el espejo. Se avisa y se ofrece usarla.
            store.setAsideMirrorLoser(mirrorValid)
            store.addBackup(mirrorValid)
            val conflict = try { recordConflict?.invoke(mirrorValid) } catch (_: Exception) { null }
            return Outcome(
                localValid,
                makeTarget(usableMirror, pending = usableMirror != null),
                SaveLoadWarning.Divergence(conflict),
            )
        }
        return when (val r = SaveResolution.resolve(local, mirrorCandidate, owned) { it in validSizes }) {
            SaveResolution.None -> Outcome(null, makeTarget(usableMirror), extraWarning)
            is SaveResolution.Load -> {
                // N1: con dos partidas válidas y distintas y un espejo que no es nuestro (un duplicado con su propio `.sav`,
                // un `.sav` ajeno con el mismo nombre…), el perdedor se aparta además fuera de la rotación: cinco
                // guardados no pueden borrarlo. Va primero: nada se toca hasta que está a salvo.
                val loser = when {
                    r.backupOther != null -> r.backupOther
                    r.installLocal && !r.quarantineLocal && !owned && localState is SaveStore.LocalSave.Present &&
                        localState.data.size in validSizes && !localState.data.contentEquals(r.data) -> localState.data
                    else -> null
                }
                loser?.let { store.setAsideMirrorLoser(it) }
                // N1-H5: si la que pierde es la partida local, se avisa de dónde quedó.
                val localSetAside = loser != null && r.backupOther == null
                // Orden de efectos: apartado → addBackup → cuarentena → instalación. Si uno falla, los siguientes no se hacen.
                r.backupOther?.let(store::addBackup)
                if (r.quarantineLocal) store.quarantineCurrent()
                if (r.installLocal) {
                    store.save(r.data)
                    store.recordReceived(r.data) // N7a: llegó de fuera (espejo): es la base del linaje
                }
                val target = makeTarget(if (r.mirrorIgnored) null else usableMirror, pending = r.updateMirror && usableMirror != null)
                val warning = when {
                    r.mirrorIgnored -> SaveLoadWarning.MirrorIgnored
                    r.quarantineLocal -> SaveLoadWarning.LocalQuarantined
                    unavailable -> SaveLoadWarning.MirrorUnavailable
                    localSetAside -> SaveLoadWarning.LocalSetAside
                    else -> extraWarning
                }
                Outcome(r.data, target, warning)
            }
            is SaveResolution.WrongSize -> Outcome(
                null,
                null,
                when {
                    r.fromMirror -> SaveLoadWarning.MirrorWrongSizeOnly
                    localState is SaveStore.LocalSave.Unreadable ->
                        SaveLoadWarning.Unreadable(localState.error.message ?: localState.error.javaClass.simpleName)
                    else -> SaveLoadWarning.LocalWrongSize
                },
            )
        }
    }
}
