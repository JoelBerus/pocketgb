package com.joelbermudez.pocketgb.travel

import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.saves.PosixSaveFileOps
import com.joelbermudez.pocketgb.saves.SaveFileOps
import com.joelbermudez.pocketgb.saves.SaveLineage
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import java.io.File
import java.io.IOException

/**
 * El juego al que va la partida. [validSizes]: tamaños de `.sav` del cartucho (los del índice o, si el juego nunca se
 * abrió, los de la cabecera del ROM, ND20 j). [config]: configuración actual del juego en este equipo (para avisar de qué
 * ajuste cambiar si el estado del paquete es de otra, ND20 h, ND21).
 */
class ImportTarget(
    /** Huella SHA-256 completa del ROM, 64 hex en minúsculas (Android compara los 32 bytes). */
    val fingerprint: String,
    /** `"gb"` o `"gba"` (= `core.name` de META). */
    val console: String,
    val hasBattery: Boolean,
    val validSizes: Set<Int>?,
    val config: PgbmConfig? = null,
    /** Nombre visible del juego (para confirmar un `.sav` crudo, ND20 e). */
    val title: String = "",
)

/**
 * N7b · importar un `.pgbm` o un `.sav` crudo (docs/12-formato-pgbm.md §Lo que debe hacer el importador; ND20). Todas las
 * comprobaciones y preguntas van ANTES de tocar nada; lo que toca la partida va bajo la propiedad exclusiva de la huella
 * (no hay sesión abierta o aparcada de ese juego, §3.3). Antes de instalar, la partida actual (con su estado automático)
 * va al anillo «Antes de importar» y a una copia apartada (ND20 f); la escritura atómica la deja además en `.1` (regla 6).
 */
class SaveImporter(
    private val savesDirectory: File,
    private val statesRoot: File,
    private val momentsRoot: File?,
    private val codec: PgbmCodec = NativePgbmCodec,
    private val ops: SaveFileOps = PosixSaveFileOps,
    private val ownership: FingerprintOwnership = FingerprintOwnership.shared,
    /** Nombre del momento «Conflicto …» (recibe el equipo de la otra partida, o [thisDevice]). */
    private val conflictName: (device: String) -> String = { "Conflicto · $it" },
    /** Nombre de la entrada del anillo con la partida de antes de importar (ND20 f). */
    private val beforeImportName: String = "Antes de importar",
    private val thisDevice: String = "este teléfono",
) {
    enum class Rejection {
        /** No es un `.pgbm` (mágico) ni un `.sav` que encaje. */
        NOT_A_PACKAGE,

        /** Cortado o corrupto (CRC, estructura, topes). */
        DAMAGED,

        /** De una versión más nueva de la app (`PGBM_ERR_VERSION`, `PGBM_ERR_CRITICAL` o `format` > 1). */
        NEWER_APP,

        /** Sin META, o META inválida, o que no corresponde a sus secciones (huellas). */
        INVALID_META,

        /** Es de otro juego (ROMF ≠ huella del juego) o de otra consola. */
        OTHER_GAME,

        /** No se conocen los tamaños válidos de la partida (ni índice ni cabecera legible). */
        UNKNOWN_SIZES,

        /** `.sav` crudo de un tamaño que el cartucho no acepta (no se toca nada). */
        WRONG_SIZE,

        /** H7: la partida de aquí existe pero no se puede leer: no se instala nada encima. */
        LOCAL_UNREADABLE,
    }

    /** Respuesta a una pregunta de [Result.NeedsChoice]: con qué partida seguir, o confirmar ([USE_INCOMING]). */
    enum class Choice { KEEP_LOCAL, USE_INCOMING }

    /** Qué hay que preguntar antes de tocar nada. */
    enum class Ask {
        /** Las dos cambiaron por separado (o el avance no se puede probar). Dos opciones. */
        DIVERGENCE,

        /** ND20 (d): la que llega ya la tuvo este equipo (historial, recibidas, copias o apartadas). Dos opciones. */
        KNOWN,

        /** ND20 (e): un `.sav` crudo siempre se confirma nombrando el juego. */
        RAW_CONFIRM,

        /** ND20 (f): el paquete trae un estado automático que sustituiría al de aquí sin cambiar la partida. */
        REPLACE_STATE,
    }

    sealed interface Result {
        data class Rejected(val reason: Rejection) : Result

        /**
         * El paquete trae una SAVE vacía o de un tamaño distinto al del cartucho (juego con batería): la partida no se
         * toca, la actual queda además en las copias y la entrante (si no está vacía) apartada (ND20 k).
         */
        data object SaveSizeMismatch : Result

        /** Hay que preguntar [ask]; no se tocó nada. Se vuelve a llamar con la respuesta. */
        data class NeedsChoice(val ask: Ask, val otherDevice: String?) : Result

        /**
         * Hecho. [lineage] dice qué pasó con la partida; [continueFrom] = nombre del equipo si quedó el estado automático
         * exacto del paquete («Continuar donde lo dejaste en <equipo>», ND6); [conflictMomentId] = la otra partida;
         * [meta] = los metadatos del paquete, para fusionarlos con los de aquí (ND20 i); [configDifferences] = ajustes del
         * juego que difieren de los del equipo de origen cuando se instaló su estado (ND21: se avisa de cuál cambiar; si
         * aun así no carga al continuar, se conserva y se juega desde la partida).
         */
        data class Done(
            val lineage: SaveLineage.Incoming,
            val installed: Boolean,
            val continueFrom: String?,
            val conflictMomentId: String? = null,
            val meta: PgbmMeta? = null,
            val configDifferences: List<PgbmConfig.Key> = emptyList(),
        ) : Result
    }

    /** Lo que se sabe del archivo antes de elegir juego: para los intents genéricos (validación por cabecera). */
    sealed interface Peek {
        data class Package(val romFingerprint: String, val meta: PgbmMeta) : Peek
        data class Rejected(val reason: Rejection) : Peek
        data object RawSave : Peek
    }

    /** Clasifica un archivo recibido por un intent genérico sin tocar nada. Sin el mágico «PGBM» se trata como `.sav`. */
    fun peek(bytes: ByteArray): Peek {
        if (!PgbmResult.hasMagic(bytes)) return Peek.RawSave
        return when (val v = validate(bytes)) {
            is Validated.Bad -> Peek.Rejected(v.reason)
            is Validated.Good -> Peek.Package(v.romHex, v.meta)
        }
    }

    private sealed interface Validated {
        class Good(val pkg: PgbmPackage, val meta: PgbmMeta, val romHex: String) : Validated
        class Bad(val reason: Rejection) : Validated
    }

    /** Comprobaciones 1–5 del importador (contenedor y META), sin juego. */
    private fun validate(bytes: ByteArray): Validated {
        if (!PgbmResult.hasMagic(bytes)) return Validated.Bad(Rejection.NOT_A_PACKAGE)
        val pkg = try {
            codec.parse(bytes)
        } catch (e: PgbmException) {
            return Validated.Bad(if (PgbmResult.needsNewerApp(e.code)) Rejection.NEWER_APP else Rejection.DAMAGED)
        }
        val metaBytes = pkg.meta ?: return Validated.Bad(Rejection.INVALID_META)
        val meta = try {
            PgbmMeta.parse(metaBytes)
        } catch (e: PgbmMeta.Invalid) {
            return Validated.Bad(if (e.newerFormat) Rejection.NEWER_APP else Rejection.INVALID_META)
        }
        val romHex = hex(pkg.romFingerprint)
        if (meta.romSha256 != romHex) return Validated.Bad(Rejection.INVALID_META)
        if (meta.savSha256 != MomentStore.sha256(pkg.sav)) return Validated.Bad(Rejection.INVALID_META)
        if (pkg.state != null && meta.stateOfSavSha256 == null) return Validated.Bad(Rejection.INVALID_META)
        return Validated.Good(pkg, meta, romHex)
    }

    /**
     * Importa un `.pgbm`. Si hay algo que preguntar devuelve [Result.NeedsChoice] sin tocar nada; se vuelve a llamar con
     * la respuesta: [choice] para elegir partida (divergencia o ya conocida) y [confirmed] con las confirmaciones dadas.
     */
    fun importPackage(bytes: ByteArray, target: ImportTarget, choice: Choice? = null, confirmed: Set<Ask> = emptySet()): Result {
        val v = when (val r = validate(bytes)) {
            is Validated.Bad -> return Result.Rejected(r.reason)
            is Validated.Good -> r
        }
        if (v.romHex != target.fingerprint.lowercase() || v.meta.coreName != target.console) return Result.Rejected(Rejection.OTHER_GAME)
        val sizes = target.validSizes
        if (target.hasBattery && sizes == null) return Result.Rejected(Rejection.UNKNOWN_SIZES)
        val device = v.meta.deviceName
        val state = v.pkg.state?.takeIf { v.meta.stateMatchesSave }
        // ND21 (como iOS): un estado de otra configuración NO se descarta; se instala igual (con las protecciones de
        // ND20 f) y el resultado dice qué ajuste cambiar. Si al continuar no carga, el núcleo lo rechaza sin tocar la
        // partida, el estado se conserva y se juega desde la partida (ExactContinuation, INCOMPATIBLE).
        val differences = target.config?.let(v.meta.config::differences).orEmpty()
        fun Result.withDifferences(): Result =
            if (this is Result.Done && continueFrom != null && differences.isNotEmpty()) copy(configDifferences = differences) else this
        return ownership.withExclusive(target.fingerprint, "importación") {
            val store = SaveStore(savesDirectory, target.fingerprint, ops)
            if (sizes != null) store.recoverOrphans(sizes)
            val sav = v.pkg.sav
            if (!target.hasBattery) {
                // Sin batería no hay partida que instalar: la SAVE debe ir vacía; el estado se puede continuar igual.
                if (sav.isNotEmpty()) return@withExclusive Result.SaveSizeMismatch
                if (state != null && hasAuto(target) && Ask.REPLACE_STATE !in confirmed) {
                    return@withExclusive Result.NeedsChoice(Ask.REPLACE_STATE, device)
                }
                val cont = state?.let { installState(target, store, null, it, device) }
                return@withExclusive Result.Done(SaveLineage.Incoming.ALREADY_CURRENT, installed = false, continueFrom = cont, meta = v.meta)
            }
            if (sav.size !in sizes!!) {
                // Regla 6: una SAVE vacía o de otro tamaño nunca sustituye la partida; la actual queda respaldada y la
                // entrante, si trae algo, apartada (ND20 k).
                (store.inspectLocal() as? SaveStore.LocalSave.Present)?.let { store.addBackup(it.data) }
                if (sav.isNotEmpty()) store.setAsideMirrorLoser(sav)
                return@withExclusive Result.SaveSizeMismatch
            }
            apply(store, target, sav, v.meta.baseSavSha256, device, choice, confirmed, v.meta, state)
        }.withDifferences()
    }

    /**
     * Importa un `.sav` crudo. Nunca trae base, así que con otra partida aquí se pregunta; y aunque no la haya, se
     * confirma nombrando el juego (ND20 e): [Ask.RAW_CONFIRM].
     */
    fun importRawSave(bytes: ByteArray, target: ImportTarget, choice: Choice? = null, confirmed: Set<Ask> = emptySet()): Result {
        if (PgbmResult.hasMagic(bytes)) return Result.Rejected(Rejection.NOT_A_PACKAGE)
        if (!target.hasBattery) return Result.Rejected(Rejection.WRONG_SIZE)
        val sizes = target.validSizes ?: return Result.Rejected(Rejection.UNKNOWN_SIZES)
        if (bytes.size !in sizes) return Result.Rejected(Rejection.WRONG_SIZE)
        return ownership.withExclusive(target.fingerprint, "importación") {
            val store = SaveStore(savesDirectory, target.fingerprint, ops)
            store.recoverOrphans(sizes)
            apply(store, target, bytes, null, null, choice, confirmed, null, null)
        }
    }

    private fun apply(
        store: SaveStore,
        target: ImportTarget,
        sav: ByteArray,
        base: String?,
        device: String?,
        choice: Choice?,
        confirmed: Set<Ask>,
        meta: PgbmMeta?,
        state: ByteArray?,
    ): Result {
        // H7: una local ilegible no se trata como ausente; una demasiado grande se aparta (cuarentena) antes de instalar.
        val localState = store.inspectLocal()
        val current = when (localState) {
            SaveStore.LocalSave.Absent, is SaveStore.LocalSave.Oversize -> null
            is SaveStore.LocalSave.Present -> localState.data
            is SaveStore.LocalSave.Unreadable -> return Result.Rejected(Rejection.LOCAL_UNREADABLE)
        }
        val incomingHash = MomentStore.sha256(sav)
        val lineage = SaveLineage.classifyIncoming(current?.let(MomentStore::sha256), incomingHash, base, store.knownHashes())
        // Preguntas, en este orden, sin tocar nada.
        when (lineage) {
            SaveLineage.Incoming.DIVERGENCE -> if (choice == null) return Result.NeedsChoice(Ask.DIVERGENCE, device)
            SaveLineage.Incoming.STALE -> if (choice == null) return Result.NeedsChoice(Ask.KNOWN, device)
            SaveLineage.Incoming.INSTALL, SaveLineage.Incoming.ADVANCE ->
                if (meta == null && choice == null && Ask.RAW_CONFIRM !in confirmed) return Result.NeedsChoice(Ask.RAW_CONFIRM, device)
            SaveLineage.Incoming.ALREADY_CURRENT -> Unit
        }
        val install = when (lineage) {
            SaveLineage.Incoming.INSTALL, SaveLineage.Incoming.ADVANCE -> true
            SaveLineage.Incoming.ALREADY_CURRENT -> false
            SaveLineage.Incoming.STALE, SaveLineage.Incoming.DIVERGENCE -> choice == Choice.USE_INCOMING
        }
        val saveIsIncoming = install || lineage == SaveLineage.Incoming.ALREADY_CURRENT
        // ND20 (f): el estado del paquete sustituiría al AUTO de aquí sin cambiar la partida → confirmar.
        if (!install && saveIsIncoming && state != null && hasAuto(target) && Ask.REPLACE_STATE !in confirmed) {
            return Result.NeedsChoice(Ask.REPLACE_STATE, device)
        }
        val moments = momentsRoot?.let { MomentStore(it, target.fingerprint, ops) }
        var conflict: String? = null
        if (!install) {
            if (lineage == SaveLineage.Incoming.DIVERGENCE || lineage == SaveLineage.Incoming.STALE) {
                // Se queda la de aquí: la que llega no se pierde (backup, apartada y, en divergencia, momento «Conflicto»).
                store.addBackup(sav)
                store.setAsideMirrorLoser(sav)
                if (lineage == SaveLineage.Incoming.DIVERGENCE) conflict = conflictMoment(moments, sav, null, null, device ?: thisDevice)
            }
            val cont = if (saveIsIncoming && state != null && device != null) installState(target, store, current, state, device) else null
            return Result.Done(lineage, installed = false, continueFrom = cont, conflictMomentId = conflict, meta = meta)
        }
        if (localState is SaveStore.LocalSave.Oversize) store.quarantineCurrent()
        val auto = readAuto(StateStore(statesRoot, target.fingerprint, ops))
        var pending: MomentStore.PendingPush? = null
        if (current != null) {
            // ND20 (f): la actual (con su AUTO) al anillo «Antes de importar» (las expulsadas se borran solo tras
            // confirmar la instalación) y a una copia apartada que no rota.
            pending = try {
                moments?.pushBeforeLoadDeferred(MomentStore.Capture(auto?.first, current, auto?.second), beforeImportName)
            } catch (_: Exception) {
                null
            }
            store.setAsideMirrorLoser(current)
            if (lineage == SaveLineage.Incoming.DIVERGENCE || lineage == SaveLineage.Incoming.STALE) {
                conflict = conflictMoment(moments, current, auto?.first, auto?.second, thisDevice)
            }
        }
        store.save(sav) // escritura atómica: la actual pasa a `.1`
        pending?.commit()
        store.recordReceived(sav)
        if (meta != null) store.recordOrigin(SaveStore.Origin(incomingHash, meta.devicePlatform, meta.deviceName, meta.createdMs))
        val cont = if (state != null && device != null) {
            installState(target, store, null, state, device, alreadyRinged = current != null)
        } else {
            null
        }
        return Result.Done(lineage, installed = true, continueFrom = cont, conflictMomentId = conflict, meta = meta)
    }

    private fun hasAuto(target: ImportTarget) = ops.exists(StateStore(statesRoot, target.fingerprint, ops).stateFile(StateSlot.AUTO))

    private fun readAuto(states: StateStore): Pair<ByteArray, ByteArray?>? {
        if (!ops.exists(states.stateFile(StateSlot.AUTO))) return null
        val state = try { states.load(StateSlot.AUTO) } catch (_: IOException) { return null }
        val thumb = try { ops.readBytes(states.thumbnailFile(StateSlot.AUTO), StateStore.MAX_THUMBNAIL_BYTES) } catch (_: IOException) { null }
        return state to thumb
    }

    /** ND20 (k): el momento «Conflicto» lleva la partida y, si es la de aquí, su estado y su miniatura. */
    private fun conflictMoment(moments: MomentStore?, sram: ByteArray, state: ByteArray?, thumb: ByteArray?, who: String): String? = try {
        moments?.create(MomentStore.Capture(state, sram, thumb), conflictName(who))?.id
    } catch (_: Exception) {
        null
    }

    /**
     * Instala `STAT` como estado automático (solo se llama si es exactamente el de la partida que queda, ND6). El AUTO de
     * aquí nunca se pisa sin copia (ND20 f): si no entró ya en el anillo con la partida, entra ahora junto con
     * [currentSav]; además se aparta. El núcleo valida el estado entero al continuar.
     */
    private fun installState(
        target: ImportTarget,
        store: SaveStore,
        currentSav: ByteArray?,
        state: ByteArray,
        device: String,
        alreadyRinged: Boolean = false,
    ): String? {
        val states = StateStore(statesRoot, target.fingerprint, ops)
        return try {
            val auto = readAuto(states)
            if (auto != null && !alreadyRinged) {
                momentsRoot?.let { MomentStore(it, target.fingerprint, ops) }
                    ?.pushBeforeLoad(MomentStore.Capture(auto.first, currentSav ?: store.load(), auto.second), beforeImportName)
            }
            states.setAsideAuto()
            states.save(state, null, StateSlot.AUTO)
            device
        } catch (_: Exception) {
            null
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
