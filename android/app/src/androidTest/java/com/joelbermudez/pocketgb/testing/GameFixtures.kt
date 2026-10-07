package com.joelbermudez.pocketgb.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.game.GameSession
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
import com.joelbermudez.pocketgb.saves.PosixSaveFileOps
import com.joelbermudez.pocketgb.saves.SaveFileOps
import com.joelbermudez.pocketgb.saves.SaveMirror
import com.joelbermudez.pocketgb.saves.SaveSizes
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.SaveTarget
import com.joelbermudez.pocketgb.saves.StateStore
import java.io.File
import java.io.IOException
import java.util.UUID

/** Espera activa con plazo; devuelve el valor final de la condición. */
fun waitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

fun tempDir(prefix: String): File {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return File(context.cacheDir, "$prefix-${UUID.randomUUID()}").apply { mkdirs() }
}

/** Operaciones de archivo con un interruptor que hace fallar la instalación del `.sav` (disco lleno simulado). */
class FailableOps(private val delegate: SaveFileOps = PosixSaveFileOps) : SaveFileOps by delegate {
    @Volatile var failSav = false

    override fun writeSynced(file: File, data: ByteArray) {
        if (failSav && file.name.endsWith(".sav.tmp")) throw IOException("disco lleno (fallo inyectado)")
        delegate.writeSynced(file, data)
    }
}

/**
 * Atasca la próxima escritura de un `.sav.tmp` (sin responder a interrupciones) hasta que el test la suelte:
 * un disco o proveedor que no contesta. [arm] la prepara y [awaitEntered] confirma que ya está dentro.
 */
class StallingOps(private val delegate: SaveFileOps = PosixSaveFileOps) : SaveFileOps by delegate {
    @Volatile private var armed = false
    @Volatile private var entered = java.util.concurrent.CountDownLatch(1)
    private val release = java.util.concurrent.atomic.AtomicReference(java.util.concurrent.CountDownLatch(1))

    fun arm() {
        entered = java.util.concurrent.CountDownLatch(1)
        release.set(java.util.concurrent.CountDownLatch(1))
        armed = true
    }

    fun awaitEntered(timeoutMs: Long = 10_000) = entered.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    fun release() = release.get().countDown()

    override fun writeSynced(file: File, data: ByteArray) {
        if (armed && file.name.endsWith(".sav.tmp")) {
            armed = false
            val latch = release.get()
            entered.countDown()
            var interrupted = false
            while (latch.count > 0) {
                try { latch.await() } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        delegate.writeSynced(file, data)
    }
}

/** Una partida abierta sobre directorios temporales, con todo lo que un test necesita comprobar. */
class OpenedGame(
    val game: GameSession,
    val store: SaveStore?,
    val states: StateStore,
    val root: File,
) : AutoCloseable {
    val session: EmulatorSession get() = game.session

    override fun close() {
        game.close()
        root.deleteRecursively()
    }
}

/**
 * Abre [rom] como lo haría el lanzador pero sin biblioteca: núcleo, partida local sobre [root], espejo
 * opcional y la sesión lista (sin arrancar). [persist] = false imita una sesión sin destino de guardado.
 */
fun openGame(
    rom: ByteArray,
    ops: SaveFileOps = PosixSaveFileOps,
    mirror: SaveMirror? = null,
    persist: Boolean = true,
    root: File = tempDir("game"),
    session: EmulatorSession = EmulatorSession(),
    autoTick: Boolean = true,
    flushTimeoutMs: Long = 3_000,
    closeGraceMs: Long = 10_000,
    closeKillWaitMs: Long = 5_000,
    ownership: FingerprintOwnership = FingerprintOwnership(),
    repairWaitMs: Long = 3_000,
    shutdownCoordinator: ((com.joelbermudez.pocketgb.saves.SaveCoordinator, Long, Long) -> com.joelbermudez.pocketgb.saves.CloseResult)? = null,
    repairSubmit: ((com.joelbermudez.pocketgb.saves.SaveCoordinator, () -> Unit) -> java.util.concurrent.Future<Unit>)? = null,
    repairThreadFactory: ((Runnable, String) -> Thread)? = null,
    /** N6: con momentos en `root/moments` (y configuración GB de prueba). */
    withMoments: Boolean = false,
): OpenedGame {
    val info = session.load(rom, 1_700_000_000)
    val fingerprint = info.fingerprintHex
    val states = StateStore(File(root, "states"), fingerprint, ops)
    var store: SaveStore? = null
    var target: SaveTarget? = null
    var baseline: ByteArray? = null
    if (persist && info.hasBattery) {
        val saveStore = SaveStore(File(root, "saves"), fingerprint, ops)
        saveStore.recoverOrphans(SaveSizes.validSizes(info.hasRtc, info.sramBytes))
        saveStore.load()?.let { session.loadSram(it) }
        store = saveStore
        target = SaveTarget(saveStore, mirror, registry = MirrorChannelRegistry())
        baseline = session.copySram()
    }
    val lease = ownership.tryAcquire(fingerprint, "prueba") ?: error("la huella ya tiene dueño")
    val game = GameSession(
        session, info, states, target, baseline,
        autoTick = autoTick, flushTimeoutMs = flushTimeoutMs,
        closeGraceMs = closeGraceMs, closeKillWaitMs = closeKillWaitMs,
        lease = lease, repairWaitMs = repairWaitMs,
        shutdownCoordinator = shutdownCoordinator ?: { c, grace, kill -> c.shutdown(grace, kill) },
        repairSubmit = repairSubmit ?: { c, block -> c.submitOnSaveThread(block) },
        repairThreadFactory = repairThreadFactory ?: { body, name -> Thread(body, name).apply { isDaemon = true } },
        moments = if (withMoments) com.joelbermudez.pocketgb.saves.MomentStore(File(root, "moments"), fingerprint, ops) else null,
        momentConfig = if (withMoments) mapOf("console" to "GB", "model" to "AUTO", "palette" to "0") else emptyMap(),
    )
    return OpenedGame(game, store, states, root)
}
