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

        /**
         * ND20 (a): la partida cambió aquí y en el espejo por separado. No se ha escrito nada; quien llama pregunta y
         * vuelve a abrir con una [DivergenceChoice]. Fechas (ms) de la local y del espejo para el diálogo.
         */
        data class Divergence(val localDateMs: Long?, val mirrorDateMs: Long?) : Refusal()
    }

    /** ND20 (a): qué partida sigue tras una divergencia al abrir. */
    enum class DivergenceChoice { KEEP_LOCAL, USE_MIRROR }

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
        /**
         * N7a: guarda la partida que no se elige en una divergencia como momento «Conflicto …»; devuelve su id. El segundo
         * argumento dice si es la local (entonces el momento lleva además su estado automático y su miniatura, ND20 k).
         */
        recordConflict: ((ByteArray, Boolean) -> String?)? = null,
        /** ND20 (a): respuesta a una divergencia; `null` = preguntar ([Refusal.Divergence]). */
        divergence: DivergenceChoice? = null,
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
        // N7a · linaje (§3.4, ND20): con local y espejo válidos y distintos decide la tabla por huellas, no por fechas.
        // ND20 (m): si el historial apunta a OTRA ubicación del espejo (un duplicado del mismo ROM con su propio `.sav`),
        // no hay linaje que valga: se usa la regla de N1 (fecha + apartado).
        val localValid = (localState as? SaveStore.LocalSave.Present)?.data?.takeIf { it.size in validSizes }
        val mirrorValid = mirrorCandidate?.data?.takeIf { it.size in validSizes }
        val historyLocation = store.mirrorLocation()
        val sameLocation = historyLocation == null || activeMirror?.location == null || historyLocation == activeMirror.location
        val lineage = if (localValid != null && mirrorValid != null && sameLocation) {
            SaveLineage.classifyMirror(
                store.contentHash(localValid), store.contentHash(mirrorValid), store.ownMirrorHashes(), store.lastOwnMirrorHashes(),
            )
        } else {
            null
        }
        val readOnly = mirrorMode == MirrorMode.ReadOnly
        val extraWarning = when (mirrorMode) {
            MirrorMode.ReadOnly -> SaveLoadWarning.MirrorReadOnly
            MirrorMode.Shared -> SaveLoadWarning.MirrorShared
            MirrorMode.ReadWrite -> null
        }
        if (localValid != null && mirrorValid != null) when (lineage) {
            SaveLineage.Mirror.EXTERNAL_CHANGE -> {
                // Cambio externo: la local no cambió desde nuestra última escritura o recepción. Se instala con backup (la
                // escritura atómica deja la local en `.1`), la local se aparta fuera de la rotación y se avisa, sea cual
                // sea la fecha (reloj desfasado).
                store.setAsideMirrorLoser(localValid)
                store.save(mirrorValid)
                store.recordReceived(mirrorValid)
                return Outcome(mirrorValid, makeTarget(usableMirror), SaveLoadWarning.ExternalChange(readOnly))
            }
            SaveLineage.Mirror.OWN_OLDER -> {
                // ND20 (c): gana la local y se reescribe el espejo, pero antes el espejo (una versión nuestra anterior,
                // quizá restaurada a propósito) se aparta en una copia que no rota. Aviso sin bloquear.
                store.setAsideMirrorLoser(mirrorValid)
                return Outcome(localValid, makeTarget(usableMirror, pending = usableMirror != null), SaveLoadWarning.MirrorOlderSetAside(readOnly))
            }
            SaveLineage.Mirror.DIVERGENCE -> {
                // ND20 (a): se pregunta SIN escribir nada (ni la local ni el espejo) hasta la elección.
                val choice = divergence ?: throw Refusal.Divergence(store.modificationDateMs, mirrorCandidate?.dateMs)
                val (keep, other) = if (choice == DivergenceChoice.KEEP_LOCAL) localValid to mirrorValid else mirrorValid to localValid
                // La que no se elige se aparta (fuera de la rotación), va a backup y queda como momento «Conflicto …».
                store.setAsideMirrorLoser(other)
                val conflict = try { recordConflict?.invoke(other, choice == DivergenceChoice.USE_MIRROR) } catch (_: Exception) { null }
                return if (choice == DivergenceChoice.KEEP_LOCAL) {
                    store.addBackup(other)
                    Outcome(keep, makeTarget(usableMirror, pending = usableMirror != null), SaveLoadWarning.Divergence(conflict, readOnly))
                } else {
                    store.save(keep) // la local pasa a `.1`
                    store.recordReceived(keep)
                    Outcome(keep, makeTarget(usableMirror), SaveLoadWarning.Divergence(conflict, readOnly))
                }
            }
            else -> Unit
        }
        val owned = false
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
