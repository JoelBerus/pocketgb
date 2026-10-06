package com.joelbermudez.pocketgb.saves

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Escribir el `.sav.tmp` se queda atascado y NO responde a interrupciones (un proveedor o disco colgado). */
private class UninterruptibleOps : SaveFileOps by PosixSaveFileOps {
    val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile var armed = true

    fun release() = release.countDown()

    override fun writeSynced(file: File, data: ByteArray) {
        if (armed && file.name.endsWith(".sav.tmp")) {
            armed = false
            entered.countDown()
            var interrupted = false
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        PosixSaveFileOps.writeSynced(file, data)
    }
}

/** SRAM falsa que imita el handle nativo: copiar tras "liberarlo" es un uso tras liberar y se cuenta. */
private class HandleSram(initial: ByteArray) : SramSource {
    @Volatile var data: ByteArray = initial
    @Volatile var handleReleased = false
    val copies = AtomicInteger()
    val useAfterFree = AtomicInteger()
    private val seq = AtomicLong()
    override fun dirtySeq(): Long {
        if (handleReleased) useAfterFree.incrementAndGet()
        return seq.get()
    }
    override fun copy(): ByteArray {
        if (handleReleased) useAfterFree.incrementAndGet()
        copies.incrementAndGet()
        return data.copyOf()
    }
    fun gameSaves(value: ByteArray) { data = value; seq.incrementAndGet() }
}

/** A5 auditoría Codex H1 (I3): el handle nunca se libera mientras el hilo de guardado siga vivo. */
class SaveCoordinatorCloseTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = AtomicLong(1_000)
    private val toClose = mutableListOf<SaveCoordinator>()

    @After fun tearDown() { toClose.forEach { runCatching { it.shutdown(100, 100) } } }

    private fun newCoordinator(sram: SramSource, ops: SaveFileOps, timeoutMs: Long = 3_000, registry: MirrorChannelRegistry = MirrorChannelRegistry(), mirror: SaveMirror? = null, mirrorWriter: ((ByteArray) -> Long?)? = null): SaveCoordinator {
        val store = SaveStore(File(tmp.newFolder(), "saves"), TEST_FP, ops)
        return SaveCoordinator(
            source = sram,
            target = SaveTarget(store, mirror, mirrorWriter = mirrorWriter, registry = registry),
            baseline = version(0),
            policy = SramFlushPolicy({ clock.get() }),
            flushTimeoutMs = timeoutMs,
            autoTick = false,
        ).also { toClose += it }
    }

    /** Arranca una escritura periódica que se queda atascada en el archivo. */
    private fun startBlockedWrite(c: SaveCoordinator, sram: HandleSram, ops: UninterruptibleOps) {
        sram.gameSaves(version(9))
        c.tickNow()
        clock.addAndGet(1_000)
        thread { runCatching { c.tickNow() } }
        assertTrue(ops.entered.await(5, TimeUnit.SECONDS))
    }

    @Test fun shutdownReportsAStuckThreadAndNeverStartsAnotherNativeCopy() {
        val ops = UninterruptibleOps()
        val sram = HandleSram(version(0))
        val c = newCoordinator(sram, ops)
        startBlockedWrite(c, sram, ops)
        val copiesBefore = sram.copies.get()

        val result = c.shutdown(graceMs = 150, killWaitMs = 150)

        assertTrue("el hilo sigue vivo: $result", result is CloseResult.SaveThreadStuck)
        assertTrue(c.isSaveThreadAlive)
        assertTrue("tras cerrar nadie acepta trabajo", c.flushSync() is FlushResult.Failed)
        assertEquals("ninguna copia nativa nueva tras cerrar", copiesBefore, sram.copies.get())

        ops.release()
        c.awaitThreadExit()
        assertFalse(c.isSaveThreadAlive)
        assertEquals(CloseResult.Closed, c.shutdown(150, 150))
        assertEquals(copiesBefore, sram.copies.get())
        assertEquals(0, sram.useAfterFree.get())
    }

    @Test fun tasksQueuedBeforeCloseSeeTheClosedGateAndNeverCopy() {
        val ops = UninterruptibleOps()
        val sram = HandleSram(version(0))
        val c = newCoordinator(sram, ops, timeoutMs = 20_000)
        startBlockedWrite(c, sram, ops)
        val copiesBefore = sram.copies.get()

        // Un vaciado ya encolado detrás de la escritura atascada, y después el cierre (con plazo largo: no interrumpe).
        val flushResult = AtomicReference<FlushResult>()
        val flush = thread { flushResult.set(c.flushSync()) }
        Thread.sleep(100)
        val closeResult = AtomicReference<CloseResult>()
        val closer = thread { closeResult.set(c.shutdown(graceMs = 20_000, killWaitMs = 5_000)) }
        Thread.sleep(100)

        ops.release()
        closer.join(10_000)
        flush.join(10_000)

        assertEquals(CloseResult.Closed, closeResult.get())
        assertTrue("el vaciado encolado no copió: ${flushResult.get()}", flushResult.get() is FlushResult.Failed)
        assertEquals("ninguna copia tras marcar el cierre", copiesBefore, sram.copies.get())
        // Solo ahora (hilo terminado) se "libera el handle", como hace GameSession.
        sram.handleReleased = true
        assertEquals(0, sram.useAfterFree.get())
    }

    @Test fun anInterruptIgnoringWriteIsOnlyReleasedAfterTheThreadReallyExits() {
        // Patrón de GameSession.tryClose: si shutdown no devuelve Closed, el handle NO se libera; un "reaper" espera.
        val ops = UninterruptibleOps()
        val sram = HandleSram(version(0))
        val c = newCoordinator(sram, ops)
        startBlockedWrite(c, sram, ops)

        val released = AtomicBoolean(false)
        val result = c.shutdown(100, 100)
        if (result == CloseResult.Closed) { sram.handleReleased = true; released.set(true) }
        val reaper = thread {
            c.awaitThreadExit()
            sram.handleReleased = true
            released.set(true)
        }
        Thread.sleep(300)
        assertFalse("el handle no se libera con el hilo vivo", released.get())
        assertTrue(c.isSaveThreadAlive)
        ops.release()
        reaper.join(10_000)
        assertTrue(released.get())
        assertFalse(c.isSaveThreadAlive)
    }

    /** A5V7-H1: `forceStop` no lanza, es idempotente y deja el hilo terminando aunque nadie haya llamado a `shutdown`. */
    @Test fun forceStopClosesTheExecutorWithoutThrowingAndIsIdempotent() {
        val sram = HandleSram(version(0))
        val c = newCoordinator(sram, PosixSaveFileOps)
        assertTrue(c.flushSync().isSafe) // asegura que el hilo de guardado existe
        assertTrue(c.isSaveThreadAlive)

        c.forceStop()
        c.forceStop()

        c.awaitThreadExit()
        assertFalse("el hilo salió", c.isSaveThreadAlive)
        assertTrue("tras forzar el cierre nadie acepta trabajo", c.flushSync() is FlushResult.Failed)
        assertEquals(CloseResult.Closed, c.shutdown(150, 150))
    }

    @Test fun flushSyncHonorsAnExplicitShorterTimeout() {
        val ops = UninterruptibleOps()
        val sram = HandleSram(version(0))
        val c = newCoordinator(sram, ops, timeoutMs = 20_000)
        startBlockedWrite(c, sram, ops)
        val started = System.nanoTime()
        assertEquals(FlushResult.TimedOut, c.flushSync(timeoutMs = 150))
        assertTrue("respetó el plazo corto", System.nanoTime() - started < 3_000_000_000L)
        ops.release()
    }

    @Test fun aBlockedMirrorNeverPilesUpIdleCallbacks() {
        val registry = MirrorChannelRegistry()
        val mirror = FakeSaveMirror()
        val blocked = BlockingWriter(mirror)
        val sram = HandleSram(version(0))
        val c = newCoordinator(sram, PosixSaveFileOps, registry = registry, mirror = mirror, mirrorWriter = blocked::write)
        repeat(6) { i ->
            sram.gameSaves(version(i + 1))
            assertTrue(c.flushSync().isSafe)
        }
        assertTrue(blocked.started.tryAcquireWithin())
        assertTrue("una sola espera pendiente, no una por guardado", registry.channel(TEST_FP).idleCallbackCount <= 1)
        blocked.unblock.release(10)
        assertTrue(waitUntil { registry.channel(TEST_FP).idleCallbackCount == 0 })
    }

    @Test fun persistenceDisabledNeverWritesAgain() {
        val sram = HandleSram(version(0))
        val store = SaveStore(File(tmp.newFolder(), "saves"), TEST_FP)
        val c = SaveCoordinator(sram, SaveTarget(store, null), baseline = version(0), policy = SramFlushPolicy({ clock.get() }), autoTick = false)
            .also { toClose += it }
        c.disablePersistence(IllegalStateException("núcleo dudoso"))
        sram.gameSaves(version(5))
        assertEquals(FlushResult.Unchanged, c.flushSync())
        c.tickNow(); clock.addAndGet(5_000); c.tickNow()
        assertEquals(null, store.load())
        assertFalse(c.hasTarget)
        assertTrue("el problema sigue visible", c.pendingError != null)
    }
}
