package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MirrorChannelTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = SaveStore(File(tmp.newFolder(), "saves"), java.util.UUID.randomUUID().toString().take(12))

    private fun idle(target: SaveTarget): Boolean {
        val latch = CountDownLatch(1)
        target.whenMirrorIdle { latch.countDown() }
        return latch.await(3, TimeUnit.SECONDS)
    }

    @Test fun mirrorFailureKeepsLocalAndRetries() {
        val s = store()
        val mirror = FakeSaveMirror().apply { failWrites = true }
        val attempts = Semaphore(0)
        val target = SaveTarget(s, mirror, mirrorWriter = { d -> attempts.release(); mirror.write(d) })
        target.persistLocal(bytes(1, 2, 3, 4))
        assertTrue(attempts.tryAcquireWithin())
        assertArrayEquals(bytes(1, 2, 3, 4), s.load()) // la local está a salvo
        assertTrue(waitUntil { idle(target) && target.mirrorPending })
        assertTrue("tras un fallo queda pendiente", target.mirrorPending)
        // Vuelve el acceso: el reintento escribe el espejo.
        mirror.failWrites = false
        target.retryMirrorIfNeeded(bytes(1, 2, 3, 4))
        assertTrue(attempts.tryAcquireWithin())
        assertTrue(waitUntil { !target.mirrorPending })
        assertArrayEquals(bytes(1, 2, 3, 4), mirror.writes.single())
    }

    @Test fun failedAttemptIsAnnotatedWriteAheadAndSuccessWithObservedDate() {
        val s = store()
        val mirror = FakeSaveMirror(dateOnWrite = 777L)
        val target = SaveTarget(s, mirror)
        target.persistLocal(bytes(5, 5, 5, 5))
        assertTrue(idle(target))
        assertTrue(waitUntil { !target.mirrorPending })
        assertTrue(s.recognizesOwnedMirror(bytes(5, 5, 5, 5), 777L))
        assertFalse(s.recognizesOwnedMirror(bytes(5, 5, 5, 5), 778L))
        assertEquals(emptyList<String>(), s.mirrorHistoryPending())
    }

    @Test fun failedWriteLeavesTheWriteAheadEntryPending() {
        val s = store()
        val mirror = FakeSaveMirror().apply { failWrites = true }
        val target = SaveTarget(s, mirror)
        target.persistLocal(bytes(6, 6, 6, 6))
        assertTrue(waitUntil { idle(target) && s.mirrorHistoryPending().isNotEmpty() })
        // El proceso pudo morir tras el replace remoto: el contenido pendiente aún se reconoce como propio.
        assertTrue(s.recognizesOwnedMirror(bytes(6, 6, 6, 6), null))
    }

    @Test fun persistWritesBothCopies() {
        val s = store()
        val mirror = FakeSaveMirror()
        val target = SaveTarget(s, mirror)
        target.persistLocal(bytes(7, 7, 7, 7))
        assertTrue(idle(target))
        assertArrayEquals(bytes(7, 7, 7, 7), s.load())
        assertArrayEquals(bytes(7, 7, 7, 7), mirror.writes.single())
        assertTrue(waitUntil { !target.mirrorPending })
    }

    @Test fun blockedMirrorDoesNotBlockLocalFlushAndCoalescesLatest() {
        val s = store()
        val mirror = FakeSaveMirror()
        val blocked = BlockingWriter(mirror)
        val target = SaveTarget(s, mirror, mirrorWriter = blocked::write)

        // Cada persistLocal en su hilo con plazo: si el espejo bloqueara la local, no terminarían.
        fun persistAsync(data: ByteArray): Boolean {
            val done = CountDownLatch(1)
            Thread { target.persistLocal(data); done.countDown() }.start()
            return done.await(3, TimeUnit.SECONDS)
        }
        assertTrue(persistAsync(bytes(1, 1, 1, 1)))
        assertTrue(blocked.started.tryAcquireWithin())
        assertTrue("la local no espera al espejo", persistAsync(bytes(2, 2, 2, 2)))
        assertTrue("la local no espera al espejo", persistAsync(bytes(3, 3, 3, 3)))
        assertArrayEquals(bytes(3, 3, 3, 3), s.load())

        blocked.unblock.release()
        assertTrue(blocked.finished.tryAcquireWithin())
        blocked.unblock.release()
        assertTrue(blocked.finished.tryAcquireWithin())
        assertTrue(idle(target))
        // Una escritura empezada no se cancela, pero [2] se descartó: solo la primera y la última.
        assertEquals(listOf(listOf(1), listOf(3)), mirror.writes.map { w -> w.map { it.toInt() }.distinct() })
        assertEquals(2, mirror.writes.size)
    }

    @Test fun sameContentAlreadyInFlightIsNotRepeated() {
        val s = store()
        val mirror = FakeSaveMirror()
        val blocked = BlockingWriter(mirror)
        val target = SaveTarget(s, mirror, mirrorWriter = blocked::write)
        target.persistLocal(bytes(4, 4, 4, 4))
        assertTrue(blocked.started.tryAcquireWithin())
        target.retryMirrorIfNeeded(bytes(4, 4, 4, 4))
        target.retryMirrorIfNeeded(bytes(4, 4, 4, 4))
        blocked.unblock.release()
        assertTrue(blocked.finished.tryAcquireWithin())
        assertTrue(idle(target))
        assertEquals(1, mirror.writes.size)
    }

    @Test fun whenMirrorIdleRunsImmediatelyWhenIdleAndAfterAFailure() {
        val s = store()
        val mirror = FakeSaveMirror().apply { failWrites = true }
        val target = SaveTarget(s, mirror)
        assertTrue(idle(target)) // sin trabajo
        target.persistLocal(bytes(1, 1, 1, 1))
        assertTrue("un fallo también libera a quien espera", idle(target))
        assertTrue(waitUntil { target.mirrorPending && idle(target) })
    }

    @Test fun noMirrorMeansNoChannel() {
        val target = SaveTarget(store(), null)
        assertFalse(target.mirrorPending)
        assertTrue(idle(target))
        target.persistLocal(bytes(1, 1, 1, 1))
        target.retryMirrorIfNeeded(bytes(1, 1, 1, 1))
        assertFalse(target.mirrorPending)
    }

    @Test fun mirrorPendingFlagMarksTheChannelForRetry() {
        val mirror = FakeSaveMirror()
        val target = SaveTarget(store(), mirror, mirrorPending = true)
        assertTrue(target.mirrorPending)
        target.retryMirrorIfNeeded(bytes(1, 1, 1, 1))
        assertTrue(waitUntil { !target.mirrorPending })
        assertEquals(1, mirror.writes.size)
    }

    @Test fun retryWithoutPendingFlagDoesNothing() {
        val mirror = FakeSaveMirror()
        val target = SaveTarget(store(), mirror)
        target.retryMirrorIfNeeded(bytes(1, 1, 1, 1))
        assertTrue(idle(target))
        assertTrue(mirror.writes.isEmpty())
    }

    @Test fun registryGivesOneSerialDaemonThreadPerFingerprint() {
        val registry = MirrorChannelRegistry()
        val a1 = registry.channel("aa")
        assertSame(a1, registry.channel("aa"))
        assertNotSame(a1, registry.channel("bb"))
        val names = CopyOnWriteArrayList<String>()
        val daemon = CopyOnWriteArrayList<Boolean>()
        val s = store()
        val mirror = FakeSaveMirror()
        val target = SaveTarget(s, mirror, mirrorWriter = {
            names += Thread.currentThread().name; daemon += Thread.currentThread().isDaemon; mirror.write(it)
        }, registry = registry)
        target.persistLocal(bytes(1, 1, 1, 1))
        assertTrue(idle(target))
        assertTrue(names.single().startsWith("pocketgb-mirror-"))
        assertTrue(daemon.single())
    }

    @Test fun writerThrowingARuntimeExceptionIsAMirrorFailureNotACrash() {
        val s = store()
        val target = SaveTarget(s, FakeSaveMirror(), mirrorWriter = { throw IllegalStateException("SAF revocado") })
        target.persistLocal(bytes(2, 2, 2, 2))
        assertTrue(idle(target))
        assertArrayEquals(bytes(2, 2, 2, 2), s.load())
        assertTrue(waitUntil { target.mirrorPending })
    }

    @Test fun localFailurePropagatesAndNeverTouchesTheMirror() {
        val mirror = FakeSaveMirror()
        val ops = FaultInjectingFileOps(failAt = 1)
        val s = SaveStore(File(tmp.newFolder(), "saves"), "zz", ops)
        val target = SaveTarget(s, mirror)
        try {
            target.persistLocal(bytes(1, 1, 1, 1)); throw AssertionError("debía fallar la local")
        } catch (_: IOException) {
        }
        assertTrue(idle(target))
        assertTrue(mirror.writes.isEmpty())
    }
}
