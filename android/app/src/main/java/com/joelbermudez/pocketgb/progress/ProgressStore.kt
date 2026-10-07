package com.joelbermudez.pocketgb.progress

import com.joelbermudez.pocketgb.saves.PosixSaveFileOps
import com.joelbermudez.pocketgb.saves.SaveFileOps
import com.joelbermudez.pocketgb.saves.SaveLocks
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Un hito de progreso del usuario (casilla). */
@Serializable
data class Milestone(val id: String, val title: String, val done: Boolean = false, val doneAtMs: Long? = null)

/** Plantillas de hitos (N6): «Pokémon: 8 medallas + Liga» y «Libre» (sin hitos). */
enum class MilestoneTemplate { FREE, POKEMON }

/**
 * N6 · progreso de un juego, por dispositivo (ND12): tiempo de juego (solo con el juego corriendo), sesiones, primera y
 * última vez, hitos opcionales del usuario y si se muestra el porcentaje (desactivado por defecto). Guarda también la
 * cabecera del ROM (0x150 bytes) para que el lector Pokémon pueda leer la partida desde el detalle sin abrir el ROM.
 */
@Serializable
data class GameProgress(
    val version: Int = 1,
    val playTimeMs: Long = 0,
    val sessions: Int = 0,
    val firstPlayedMs: Long? = null,
    val lastPlayedMs: Long? = null,
    val template: MilestoneTemplate? = null,
    val milestones: List<Milestone> = emptyList(),
    val showPercent: Boolean = false,
    val romHeaderHex: String? = null,
) {
    /** Porcentaje de hitos hechos (0–100), o `null` sin hitos. */
    val percent: Int? get() = if (milestones.isEmpty()) null else milestones.count { it.done } * 100 / milestones.size

    val romHeader: ByteArray? get() = romHeaderHex?.takeIf { it.length % 2 == 0 }?.let { hex ->
        try { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (_: NumberFormatException) { null }
    }

    companion object {
        const val MAX_MILESTONES = 64
        const val MAX_TITLE = 60

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** `progress/<huella>.json`, escrito con temporal + `fsync` + `rename`. Un archivo ilegible vale como vacío y se aparta al escribir. */
class ProgressStore(
    val directory: File,
    private val ops: SaveFileOps = PosixSaveFileOps,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun file(fingerprint: String): File {
        require(FINGERPRINT.matches(fingerprint)) { "Huella no válida" }
        return File(directory, "$fingerprint.json")
    }

    fun load(fingerprint: String): GameProgress = read(file(fingerprint)) ?: GameProgress()

    /** Lee, transforma y escribe bajo el lock de esa huella. Devuelve el resultado. */
    fun update(fingerprint: String, transform: (GameProgress) -> GameProgress): GameProgress =
        SaveLocks.forSave(directory, "#progress-$fingerprint").withLock {
            val target = file(fingerprint)
            val current = read(target)
            if (current == null && ops.exists(target)) {
                // Ilegible: no se pisa, se aparta (nunca se pierde lo que hubiera).
                ops.atomicReplace(target, File(directory, "$fingerprint.damaged-${now()}.json"))
            }
            val next = transform(current ?: GameProgress()).let { p ->
                p.copy(milestones = p.milestones.take(GameProgress.MAX_MILESTONES).map { it.copy(title = it.title.trim().take(GameProgress.MAX_TITLE)) })
            }
            if (next != current) {
                if (!ops.exists(directory)) ops.mkdirs(directory)
                val tmp = File(target.path + ".tmp")
                ops.writeSynced(tmp, json.encodeToString(GameProgress.serializer(), next).toByteArray(Charsets.UTF_8))
                ops.atomicReplace(tmp, target)
                ops.syncDirectory(directory)
            }
            next
        }

    // ------------------------------------------------------------------ hitos

    fun applyTemplate(fingerprint: String, template: MilestoneTemplate, titles: List<String>) = update(fingerprint) { p ->
        // Los hitos ya marcados se conservan; la plantilla añade los que falten por título.
        val existing = p.milestones.map { it.title.lowercase() }.toSet()
        val added = titles.filter { it.lowercase() !in existing }.map { Milestone(newId(), it) }
        p.copy(template = template, milestones = p.milestones + added)
    }

    fun addMilestone(fingerprint: String, title: String) = update(fingerprint) { p ->
        val clean = title.trim()
        if (clean.isEmpty()) p else p.copy(milestones = p.milestones + Milestone(newId(), clean))
    }

    fun setDone(fingerprint: String, id: String, done: Boolean) = update(fingerprint) { p ->
        p.copy(milestones = p.milestones.map { if (it.id == id) it.copy(done = done, doneAtMs = if (done) now() else null) else it })
    }

    /** Marca como hechos los [count] primeros hitos de la lista (propuesta del lector Pokémon: medallas). */
    fun markFirst(fingerprint: String, ids: List<String>) = update(fingerprint) { p ->
        p.copy(milestones = p.milestones.map { if (it.id in ids && !it.done) it.copy(done = true, doneAtMs = now()) else it })
    }

    fun removeMilestone(fingerprint: String, id: String) = update(fingerprint) { p -> p.copy(milestones = p.milestones.filter { it.id != id }) }

    fun setShowPercent(fingerprint: String, show: Boolean) = update(fingerprint) { it.copy(showPercent = show) }

    fun recordHeader(fingerprint: String, header: ByteArray) {
        val hex = GameProgress.hex(header)
        if (load(fingerprint).romHeaderHex != hex) update(fingerprint) { it.copy(romHeaderHex = hex) }
    }

    private fun read(file: File): GameProgress? = try {
        if (!ops.exists(file)) GameProgress() else json.decodeFromString(GameProgress.serializer(), ops.readBytes(file, 1 shl 20).toString(Charsets.UTF_8))
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}

/**
 * Contabilidad del tiempo de juego (N6): cuenta solo mientras la sesión corre ([onRunning]). Cada transición a «no
 * corriendo» (pausa, segundo plano, salida) lo escribe, y [checkpoint] (cada 30 s desde el ViewModel) acota lo que se
 * pierde con un cierre forzado. Una sesión se cuenta la primera vez que el juego corre.
 */
class PlayTimeTracker(
    private val store: ProgressStore,
    private val fingerprint: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wall: () -> Long = System::currentTimeMillis,
) {
    private var runningSince: Long? = null
    private var counted = false

    @Synchronized fun onRunning(running: Boolean) {
        if (running) {
            if (runningSince == null) runningSince = clock()
            if (!counted) {
                counted = true
                val at = wall()
                safely { store.update(fingerprint) { it.copy(sessions = it.sessions + 1, firstPlayedMs = it.firstPlayedMs ?: at, lastPlayedMs = at) } }
            }
        } else {
            flush(keepRunning = false)
        }
    }

    /** Escribe lo acumulado sin parar la cuenta. */
    @Synchronized fun checkpoint() = flush(keepRunning = true)

    /** Lo acumulado y aún sin escribir (pruebas). */
    @Synchronized fun pendingMs(): Long = runningSince?.let { clock() - it } ?: 0L

    private fun flush(keepRunning: Boolean) {
        val since = runningSince ?: return
        val t = clock()
        val elapsed = (t - since).coerceAtLeast(0)
        runningSince = if (keepRunning) t else null
        if (elapsed == 0L) return
        val at = wall()
        safely { store.update(fingerprint) { it.copy(playTimeMs = it.playTimeMs + elapsed, lastPlayedMs = at) } }
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: Exception) {}
    }
}
