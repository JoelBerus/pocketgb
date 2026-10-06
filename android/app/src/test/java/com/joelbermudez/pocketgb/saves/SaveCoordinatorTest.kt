package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SRAM falsa: `write` simula que el juego guarda (sube el contador de guardados). */
private class FakeSram(initial: ByteArray) : SramSource {
    @Volatile var data: ByteArray = initial
    private val seq = AtomicLong()
    val copyThreads = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun dirtySeq(): Long = seq.get()
    override fun copy(): ByteArray {
        copyThreads += Thread.currentThread().name
        return data.copyOf()
    }
    fun gameSaves(value: ByteArray) {
        data = value
        seq.incrementAndGet()
    }
}

/** Ops que cuentan escrituras y pueden bloquear la primera hasta que el test la suelte. */
private class GateOps : SaveFileOps by PosixSaveFileOps {
    val writes = AtomicInteger()
    val written = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
    @Volatile var gateFirst = false
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun writeSynced(file: File, data: ByteArray) {
        val n = writes.incrementAndGet()
        if (file.name.endsWith(".sav.tmp")) written += data.copyOf()
        if (gateFirst && n == 1) {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        PosixSaveFileOps.writeSynced(file, data)
    }
}

class SaveCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = AtomicLong(1_000)
    private val coordinators = mutableListOf<SaveCoordinator>()

    @After fun tearDown() {
        coordinators.forEach { runCatching { it.close() } }
    }

    private fun policy() = SramFlushPolicy({ clock.get() })

    private fun store(ops: SaveFileOps = PosixSaveFileOps) = SaveStore(File(tmp.newFolder(), "saves"), TEST_FP, ops)

    private fun coordinator(
        sram: FakeSram,
        target: SaveTarget?,
        baseline: ByteArray? = version(0),
        timeoutMs: Long = 3_000,
        onStatus: (Throwable?) -> Unit = {},
        onMirrorTrouble: () -> Unit = {},
    ) = SaveCoordinator(
        source = sram,
        target = target,
        baseline = baseline,
        policy = policy(),
        flushTimeoutMs = timeoutMs,
        autoTick = false,
        onStatus = onStatus,
        onMirrorTrouble = onMirrorTrouble,
    ).also { coordinators += it }

    @Test fun syncFlushSavesChangesAndReportsUnchangedWhenEqual() {
        val store = store()
        val sram = FakeSram(version(0))
        val c = coordinator(sram, SaveTarget(store, null))
        assertEquals(FlushResult.Unchanged, c.flushSync()) // baseline = lo que hay: no se escribe
        assertNull(store.load())
        sram.gameSaves(version(7))
        assertEquals(FlushResult.Saved, c.flushSync())
        assertArrayEquals(version(7), store.load())
        assertEquals(FlushResult.Unchanged, c.flushSync())
        assertEquals(3, c.flushDurationsNanos().size)
    }

    @Test fun copyAndWriteHappenOnTheSaveThread() {
        val sram = FakeSram(version(0))
        val c = coordinator(sram, SaveTarget(store(), null))
        sram.gameSaves(version(1))
        c.flushSync()
        c.tickNow()
        assertTrue(sram.copyThreads.isNotEmpty())
        assertTrue(sram.copyThreads.all { it == SaveCoordinator.THREAD_NAME })
    }

    @Test fun syncFlushRetriesAnAsyncWriteThatFailed() {
        val faulty = FaultInjectingFileOps(failAt = 1)
        val store = store(faulty)
        val sram = FakeSram(version(0))
        val statuses = mutableListOf<Throwable?>()
        val c = coordinator(sram, SaveTarget(store, null), onStatus = { statuses += it })
        sram.gameSaves(version(5))
        c.tickNow() // el ciclo ve el guardado del juego
        clock.addAndGet(1_000)
        c.tickNow() // escritura periódica: falla (fallo inyectado en la primera operación)
        assertNotNull(c.pendingError)
        assertNull(store.load())
        // El vaciado síncrono NO se fía de "lo último encolado": compara con lo confirmado y reescribe.
        assertEquals(FlushResult.Saved, c.flushSync())
        assertArrayEquals(version(5), store.load())
        assertNull(c.pendingError)
        assertEquals(2, statuses.size) // error, y luego resuelto
        assertNull(statuses.last())
    }

    @Test fun aFailedPeriodicWriteIsRetriedByTheNextDueTick() {
        val faulty = FaultInjectingFileOps(failAt = 1)
        val store = store(faulty)
        val sram = FakeSram(version(0))
        val c = coordinator(sram, SaveTarget(store, null))
        sram.gameSaves(version(2))
        c.tickNow()
        clock.addAndGet(1_000)
        c.tickNow()
        assertNull(store.load())
        clock.addAndGet(500)
        c.tickNow() // aún dentro del debounce de reintento
        assertNull(store.load())
        clock.addAndGet(600)
        c.tickNow()
        assertArrayEquals(version(2), store.load())
        assertNull(c.pendingError)
    }

    @Test fun requestFlushRetriesAFailedSyncFlushWithoutANewGameSave() {
        val faulty = FaultInjectingFileOps(failAt = 1)
        val store = store(faulty)
        val sram = FakeSram(version(0))
        val c = coordinator(sram, SaveTarget(store, null))
        sram.gameSaves(version(8))
        assertTrue(c.flushSync() is FlushResult.Failed) // escritura fallida al pausar
        assertNull(store.load())
        // Al volver a primer plano el juego sigue en pausa (sin nuevos guardados): se pide el reintento.
        c.requestFlush()
        c.tickNow()
        clock.addAndGet(1_000)
        c.tickNow()
        assertArrayEquals(version(8), store.load())
        assertNull(c.pendingError)
    }

    @Test fun debounceWaitsForQuietBeforeWriting() {
        val ops = GateOps()
        val store = store(ops)
        val sram = FakeSram(version(0))
        val c = coordinator(sram, SaveTarget(store, null))
        sram.gameSaves(version(1))
        c.tickNow()
        clock.addAndGet(999)
        sram.gameSaves(version(2)) // otro guardado: reinicia la espera
        c.tickNow()
        clock.addAndGet(999)
        c.tickNow()
        assertEquals(0, ops.writes.get())
        clock.addAndGet(1)
        c.tickNow()
        assertArrayEquals(version(2), store.load())
        assertEquals(1, ops.written.size) // coalescencia: solo la última versión
    }

    @Test fun identicalContentIsNotWrittenTwice() {
        val ops = GateOps()
        val store = store(ops)
        val sram = FakeSram(version(0))
        val c = coordinator(sram, SaveTarget(store, null))
        sram.gameSaves(version(3))
        c.tickNow()
        clock.addAndGet(1_000)
        c.tickNow()
        val afterFirst = ops.writes.get()
        sram.gameSaves(version(3)) // el juego "guarda" lo mismo
        c.tickNow()
        clock.addAndGet(1_000)
        c.tickNow()
        assertEquals(afterFirst, ops.writes.get())
    }

    @Test fun safetyNetSavesAfter60SecondsWithoutAnEdge() {
        val store = store()
        val sram = FakeSram(version(0))
        val c = coordinator(sram, SaveTarget(store, null))
        sram.data = version(4) // la SRAM cambió pero el juego nunca cerró la RAM: sin flanco
        c.tickNow()
        assertNull(store.load())
        clock.addAndGet(60_000)
        c.tickNow()
        assertArrayEquals(version(4), store.load())
    }

    @Test fun stateLoadedWhileAnAsyncWriteIsInFlightEndsWithDiskEqualToCore() {
        // ORDEN I4: la escritura periódica de A está en vuelo (bloqueada) cuando se carga un estado (B).
        // El vaciado síncrono va detrás en la misma cola, copia B DESPUÉS y lo escribe: disco == núcleo.
        val ops = GateOps().apply { gateFirst = true }
        val store = store(ops)
        val sram = FakeSram(version(0))
        val c = coordinator(sram, SaveTarget(store, null), timeoutMs = 20_000)
        sram.gameSaves(version(0xA))
        c.tickNow()
        clock.addAndGet(1_000)
        val tick = thread { c.tickNow() }
        assertTrue(ops.entered.await(5, TimeUnit.SECONDS))

        sram.gameSaves(version(0xB)) // loadStateRaw: el núcleo ahora tiene B
        val result = AtomicReference<FlushResult>()
        val flush = thread { result.set(c.flushSync()) }
        Thread.sleep(100)
        assertNull("el vaciado espera detrás de la escritura en vuelo", result.get())
        ops.release.countDown()
        tick.join(5_000)
        flush.join(5_000)

        assertEquals(FlushResult.Saved, result.get())
        assertArrayEquals(version(0xB), store.load())
        assertArrayEquals(sram.data, store.load())
        assertEquals(listOf(0xA, 0xB), ops.written.map { it[0].toInt() }) // A antes que B, nunca al revés
    }

    @Test fun syncFlushTimesOutWhenTheSaveThreadIsBlockedAndKeepsItPending() {
        val ops = GateOps().apply { gateFirst = true }
        val store = store(ops)
        val sram = FakeSram(version(0))
        val c = coordinator(sram, SaveTarget(store, null), timeoutMs = 200)
        sram.gameSaves(version(9))
        c.tickNow()
        clock.addAndGet(1_000)
        val tick = thread { c.tickNow() }
        assertTrue(ops.entered.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertEquals(FlushResult.TimedOut, c.flushSync())
        assertTrue("el plazo se respeta", (System.nanoTime() - started) < 3_000_000_000L)
        assertNotNull(c.pendingError)
        ops.release.countDown()
        tick.join(5_000)
        // La tarea encolada tras el plazo sigue y, al terminar, el siguiente vaciado confirma.
        assertTrue(waitUntil { runCatching { c.flushSync() }.getOrNull()?.isSafe == true })
        assertNull(c.pendingError)
        assertArrayEquals(version(9), store.load())
    }

    @Test fun noTargetNeverWritesButStillRunsStateWorkOnTheSaveThread() {
        val c = coordinator(FakeSram(version(0)), null)
        assertEquals(FlushResult.Unchanged, c.flushSync())
        assertEquals(SaveCoordinator.THREAD_NAME, c.runOnSaveThread { Thread.currentThread().name })
        assertFalse(c.hasTarget)
    }

    @Test fun aFailingSourceIsAFlushFailureNotACrash() {
        val bad = object : SramSource {
            override fun dirtySeq() = 0L
            override fun copy(): ByteArray = throw IllegalStateException("núcleo cerrado")
        }
        val c = SaveCoordinator(bad, SaveTarget(store(), null), baseline = version(0), autoTick = false).also { coordinators += it }
        val result = c.flushSync()
        assertTrue(result is FlushResult.Failed)
        assertNotNull(c.pendingError)
    }

    @Test fun mirrorTroubleIsReportedOnceAndNeverFailsTheLocalSave() {
        val store = store()
        val mirror = FakeSaveMirror().apply { failWrites = true }
        val trouble = AtomicInteger()
        val target = SaveTarget(store, mirror, registry = MirrorChannelRegistry())
        val sram = FakeSram(version(0))
        val c = coordinator(sram, target, onMirrorTrouble = { trouble.incrementAndGet() })
        sram.gameSaves(version(6))
        assertEquals(FlushResult.Saved, c.flushSync())
        assertArrayEquals(version(6), store.load())
        assertTrue(waitUntil { trouble.get() == 1 })
        sram.gameSaves(version(7))
        assertEquals(FlushResult.Saved, c.flushSync())
        Thread.sleep(150)
        assertEquals("un aviso por racha, no uno por guardado", 1, trouble.get())
    }

    @Test fun closeJoinsTheSaveThread() {
        fun alive() = Thread.getAllStackTraces().keys.count { it.name == SaveCoordinator.THREAD_NAME && it.isAlive }
        val before = alive()
        val c = SaveCoordinator(FakeSram(version(0)), SaveTarget(store(), null), baseline = version(0)).also { coordinators += it }
        c.flushSync()
        assertEquals(before + 1, alive())
        c.close()
        assertEquals(before, alive())
        assertTrue(c.flushSync() is FlushResult.Failed) // tras cerrar ya no acepta trabajo
    }

    @Test fun ioFailureDuringBackupRotationLeavesPreviousSaveIntact() {
        // Fallo en la rotación de backups (3.ª operación): el .sav anterior debe seguir completo.
        val store0 = store()
        store0.save(version(1))
        val faulty = FaultInjectingFileOps(failAt = 3)
        val store = SaveStore(store0.directory, TEST_FP, faulty)
        val sram = FakeSram(version(1))
        val c = coordinator(sram, SaveTarget(store, null), baseline = version(1))
        sram.gameSaves(version(2))
        val result = c.flushSync()
        assertTrue(result is FlushResult.Failed)
        assertArrayEquals(version(1), store0.load())
        faulty.failAt = null
        assertEquals(FlushResult.Saved, c.flushSync())
        assertArrayEquals(version(2), store0.load())
    }

    @Suppress("unused")
    private fun io(): IOException = IOException()
}
