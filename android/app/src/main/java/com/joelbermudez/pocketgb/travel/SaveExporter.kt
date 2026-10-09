package com.joelbermudez.pocketgb.travel

import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.saves.PosixSaveFileOps
import com.joelbermudez.pocketgb.saves.SaveFileOps
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

/**
 * N7b/N7c · exportar la partida de un juego: el `.sav` crudo (sirve en otros emuladores) o un `.pgbm` con la partida,
 * el estado automático si es el de esa partida (continuación exacta, ND6) y META v1. Solo lee: nunca toca la partida.
 */
class SaveExporter(
    private val savesDirectory: File,
    private val statesRoot: File,
    private val codec: PgbmCodec = NativePgbmCodec,
    private val ops: SaveFileOps = PosixSaveFileOps,
) {
    class Info(
        val fingerprint: String,
        /** `"gb"` o `"gba"`. */
        val console: String,
        val deviceName: String,
        val coreVersion: String,
        val title: String? = null,
        val alias: String? = null,
        val tags: List<String>? = null,
        val playTimeMs: Long? = null,
        val config: Map<String, JsonPrimitive> = emptyMap(),
        val milestones: List<PgbmMeta.Milestone>? = null,
    )

    /** No hay partida que exportar (el juego nunca guardó). */
    class NoSaveException : java.io.IOException("Este juego aún no tiene partida")

    /** El `.sav` tal cual, o [NoSaveException]. */
    fun rawSave(fingerprint: String): ByteArray = SaveStore(savesDirectory, fingerprint, ops).load() ?: throw NoSaveException()

    /**
     * El paquete. Sin `.sav` local la SAVE va vacía (juego sin batería). El estado automático va solo si no está dañado
     * y no es anterior a la partida (el mismo criterio que «Continuar»); entonces `state_of_sav_sha256` = `sav_sha256`.
     */
    fun buildPackage(info: Info, nowMs: Long = System.currentTimeMillis(), includeState: Boolean = true): ByteArray {
        val store = SaveStore(savesDirectory, info.fingerprint, ops)
        val sav = store.load() ?: ByteArray(0)
        val savHash = MomentStore.sha256(sav)
        val states = StateStore(statesRoot, info.fingerprint, ops)
        val state = if (includeState && states.automaticEntry(store.modificationDateMs) != null) {
            try { states.load(StateSlot.AUTO).takeIf { it.size <= 1_048_576 } } catch (_: java.io.IOException) { null }
        } else {
            null
        }
        val meta = PgbmMeta(
            romSha256 = info.fingerprint.lowercase(),
            savSha256 = savHash,
            baseSavSha256 = store.lineageBase(),
            devicePlatform = "android",
            deviceName = info.deviceName.take(128),
            createdMs = nowMs,
            coreName = info.console,
            coreVersion = info.coreVersion.take(32),
            config = info.config,
            stateOfSavSha256 = state?.let { savHash },
            playTimeMs = info.playTimeMs,
            title = info.title?.take(256),
            alias = info.alias?.take(256),
            tags = info.tags?.take(64)?.map { it.take(64) },
            milestones = info.milestones?.take(256),
        )
        return codec.encode(PgbmPackage(hexToBytes(info.fingerprint), meta.toJson(), sav, state, null))
    }

    /** Nombre de archivo seguro: `<título>.pgbm` / `.sav`, sin separadores ni caracteres raros. */
    fun fileName(title: String, extension: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().take(80).ifEmpty { "Partida" }
        return "$clean.$extension"
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
