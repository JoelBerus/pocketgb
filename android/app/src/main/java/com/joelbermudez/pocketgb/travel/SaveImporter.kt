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

/** El juego al que va la partida. [validSizes] `null` = no se conocen (el juego nunca se abrió): no se importa. */
class ImportTarget(
    /** Huella SHA-256 completa del ROM, 64 hex en minúsculas (Android compara los 32 bytes). */
    val fingerprint: String,
    /** `"gb"` o `"gba"` (= `core.name` de META). */
    val console: String,
    val hasBattery: Boolean,
    val validSizes: Set<Int>?,
)

/**
 * N7b · importar un `.pgbm` o un `.sav` crudo (docs/12-formato-pgbm.md §Lo que debe hacer el importador). Todas las
 * comprobaciones van ANTES de tocar nada; lo que toca la partida va bajo la propiedad exclusiva de la huella (no hay
 * sesión abierta o aparcada de ese juego, §3.3) y respalda lo actual: la escritura atómica de [SaveStore] deja la
 * anterior en `.1`, y lo que no se instala queda en backup y apartado (regla 6).
 */
class SaveImporter(
    private val savesDirectory: File,
    private val statesRoot: File,
    private val momentsRoot: File?,
    private val codec: PgbmCodec = NativePgbmCodec,
    private val ops: SaveFileOps = PosixSaveFileOps,
    private val ownership: FingerprintOwnership = FingerprintOwnership.shared,
    /** Nombre del momento «Conflicto …» (recibe el equipo de la otra partida). */
    private val conflictName: (device: String) -> String = { "Conflicto · $it" },
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

        /** El juego aún no se abrió nunca aquí: no se conocen los tamaños válidos de su partida. */
        UNKNOWN_SIZES,

        /** `.sav` crudo de un tamaño que el cartucho no acepta (no se toca nada). */
        WRONG_SIZE,
    }

    enum class Choice { KEEP_LOCAL, USE_INCOMING }

    sealed interface Result {
        data class Rejected(val reason: Rejection) : Result

        /**
         * El paquete trae una SAVE vacía o de un tamaño distinto al del cartucho (juego con batería): la partida no se
         * toca y la actual queda además en las copias de seguridad.
         */
        data object SaveSizeMismatch : Result

        /** Divergencia: las dos cambiaron por separado; no se tocó nada y hay que elegir ([Choice]). */
        data class NeedsChoice(val otherDevice: String?) : Result

        /**
         * Hecho. [lineage] dice qué pasó con la partida; [continueFrom] = nombre del equipo si quedó el estado automático
         * exacto del paquete («Continuar donde lo dejaste en <equipo>», ND6); [conflictMomentId] = la otra partida.
         */
        data class Done(
            val lineage: SaveLineage.Incoming,
            val installed: Boolean,
            val continueFrom: String?,
            val conflictMomentId: String? = null,
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

    /** Importa un `.pgbm`. Con divergencia y [choice] `null` devuelve [Result.NeedsChoice] sin tocar nada. */
    fun importPackage(bytes: ByteArray, target: ImportTarget, choice: Choice? = null): Result {
        val v = when (val r = validate(bytes)) {
            is Validated.Bad -> return Result.Rejected(r.reason)
            is Validated.Good -> r
        }
        if (v.romHex != target.fingerprint.lowercase() || v.meta.coreName != target.console) return Result.Rejected(Rejection.OTHER_GAME)
        val sizes = target.validSizes
        if (target.hasBattery && sizes == null) return Result.Rejected(Rejection.UNKNOWN_SIZES)
        return ownership.withExclusive(target.fingerprint, "importación") {
            val store = SaveStore(savesDirectory, target.fingerprint, ops)
            if (sizes != null) store.recoverOrphans(sizes)
            val sav = v.pkg.sav
            if (!target.hasBattery) {
                // Sin batería no hay partida que instalar: la SAVE debe ir vacía; el estado se puede continuar igual.
                if (sav.isNotEmpty()) return@withExclusive Result.SaveSizeMismatch
                val cont = installState(target, v)
                return@withExclusive Result.Done(SaveLineage.Incoming.ALREADY_CURRENT, installed = false, continueFrom = cont)
            }
            if (sav.size !in sizes!!) {
                // Regla 6: una SAVE vacía o de otro tamaño nunca sustituye la partida; la actual queda respaldada.
                store.load()?.let(store::addBackup)
                return@withExclusive Result.SaveSizeMismatch
            }
            applyLineage(store, target, sav, v.meta.baseSavSha256, v.meta.deviceName, choice, v)
        }
    }

    /** Importa un `.sav` crudo (sin linaje propio: nunca trae base, así que con otra partida aquí se pregunta). */
    fun importRawSave(bytes: ByteArray, target: ImportTarget, choice: Choice? = null): Result {
        if (PgbmResult.hasMagic(bytes)) return Result.Rejected(Rejection.NOT_A_PACKAGE)
        if (!target.hasBattery) return Result.Rejected(Rejection.WRONG_SIZE)
        val sizes = target.validSizes ?: return Result.Rejected(Rejection.UNKNOWN_SIZES)
        if (bytes.size !in sizes) return Result.Rejected(Rejection.WRONG_SIZE)
        return ownership.withExclusive(target.fingerprint, "importación") {
            val store = SaveStore(savesDirectory, target.fingerprint, ops)
            store.recoverOrphans(sizes)
            applyLineage(store, target, bytes, null, null, choice, null)
        }
    }

    private fun applyLineage(
        store: SaveStore,
        target: ImportTarget,
        sav: ByteArray,
        base: String?,
        device: String?,
        choice: Choice?,
        pkg: Validated.Good?,
    ): Result {
        val current = try { store.load() } catch (_: IOException) { null }
        val incomingHash = MomentStore.sha256(sav)
        val lineage = SaveLineage.classifyIncoming(current?.let(MomentStore::sha256), incomingHash, base, store.knownHashes())
        if (lineage == SaveLineage.Incoming.DIVERGENCE && choice == null) return Result.NeedsChoice(device)
        val moments = momentsRoot?.let { MomentStore(it, target.fingerprint, ops) }
        fun keepAside(data: ByteArray, who: String?): String? {
            store.setAsideMirrorLoser(data)
            return try {
                moments?.create(MomentStore.Capture(null, data, null), conflictName(who ?: "?"))?.id
            } catch (_: Exception) {
                null
            }
        }
        var conflict: String? = null
        val install = when (lineage) {
            SaveLineage.Incoming.INSTALL, SaveLineage.Incoming.ADVANCE -> true
            SaveLineage.Incoming.ALREADY_CURRENT -> false
            SaveLineage.Incoming.STALE -> {
                store.addBackup(sav) // ya la tuvimos: no se instala, pero tampoco se pierde
                false
            }
            SaveLineage.Incoming.DIVERGENCE -> if (choice == Choice.USE_INCOMING) {
                conflict = keepAside(current!!, null)
                true
            } else {
                conflict = keepAside(sav, device)
                store.addBackup(sav)
                false
            }
        }
        if (install) {
            store.save(sav) // escritura atómica: la actual pasa a `.1`
            store.recordReceived(sav)
            if (pkg != null) {
                store.recordOrigin(SaveStore.Origin(incomingHash, pkg.meta.devicePlatform, pkg.meta.deviceName, pkg.meta.createdMs))
            }
        }
        val saveIsIncoming = install || lineage == SaveLineage.Incoming.ALREADY_CURRENT
        val cont = if (pkg != null && saveIsIncoming) installState(target, pkg) else null
        return Result.Done(lineage, install, cont, conflict)
    }

    /**
     * Instala `STAT` como estado automático solo si es exactamente el de la partida que queda (ND6). El anterior se
     * aparta (nunca se borra). El núcleo lo valida entero al continuar (`gb_state_load` / `gba_state_load`): si no vale,
     * la partida sigue intacta y «Continuar» lo rechaza como cualquier estado obsoleto.
     */
    private fun installState(target: ImportTarget, v: Validated.Good): String? {
        val state = v.pkg.state ?: return null
        if (!v.meta.stateMatchesSave) return null
        val states = StateStore(statesRoot, target.fingerprint, ops)
        return try {
            states.setAsideAuto()
            states.save(state, null, StateSlot.AUTO)
            v.meta.deviceName
        } catch (_: IOException) {
            null
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
