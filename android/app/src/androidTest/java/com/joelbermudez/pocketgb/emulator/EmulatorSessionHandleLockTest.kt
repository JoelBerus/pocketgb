package com.joelbermudez.pocketgb.emulator

import com.joelbermudez.pocketgb.testing.SyntheticRom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handle seguro (A5 auditoría, DeepSeek H1 / Opus H2): toda llamada nativa con handle ocurre bajo
 * `handleLock.read`, y `close()` (lock de escritura) espera a que acaben. Se prueba de forma determinista
 * sosteniendo el lock desde el test, sin depender de la suerte de un uso tras liberar.
 */
class EmulatorSessionHandleLockTest {
    private fun parked(): EmulatorSession = EmulatorSession().also {
        it.load(SyntheticRom.romOnly())
        it.start()
        it.pause()
    }

    /** Mantiene el lock de ESCRITURA mientras el test comprueba que [call] se queda esperando. */
    private fun assertWaitsForTheHandleLock(session: EmulatorSession, name: String, call: (EmulatorSession) -> Unit) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            session.handleLock.writeLock().lock()
            try {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            } finally {
                session.handleLock.writeLock().unlock()
            }
        }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        val caller = thread { call(session); done.countDown() }
        assertFalse("$name no debe usar el handle sin el lock de lectura", done.await(300, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue("$name termina al soltar el lock", done.await(5, TimeUnit.SECONDS))
        holder.join(5_000)
        caller.join(5_000)
    }

    @Test fun everyHandleUserWaitsForAnInProgressClose() {
        parked().use { s ->
            assertWaitsForTheHandleLock(s, "setTouchButtons") { it.setTouchButtons(1) }
            assertWaitsForTheHandleLock(s, "setPhysicalButtons") { it.setPhysicalButtons(1) }
            assertWaitsForTheHandleLock(s, "detachSurface") { it.detachSurface() }
            assertWaitsForTheHandleLock(s, "setSpeed") { it.setSpeed(2) }
            assertWaitsForTheHandleLock(s, "saveState") { it.saveState() }
            assertWaitsForTheHandleLock(s, "loadSram") { runCatching { it.loadSram(ByteArray(1)) } }
            assertWaitsForTheHandleLock(s, "sramDirtySequence") { it.sramDirtySequence() }
            assertWaitsForTheHandleLock(s, "copySram") { runCatching { it.copySram() } }
            assertWaitsForTheHandleLock(s, "frameCount") { it.frameCount }
        }
    }

    @Test fun closeWaitsForReadersAndLateCallsAreHarmless() {
        val s = parked()
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val reader = thread {
            s.handleLock.readLock().lock()
            try {
                reading.countDown()
                letGo.await(10, TimeUnit.SECONDS)
            } finally {
                s.handleLock.readLock().unlock()
            }
        }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        val closed = CountDownLatch(1)
        val closer = thread { s.close(); closed.countDown() }
        assertFalse("close() espera a quien usa el handle", closed.await(300, TimeUnit.MILLISECONDS))
        assertEquals("el handle sigue vivo mientras hay un lector", SessionState.Paused, s.state.value)
        letGo.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        reader.join(5_000); closer.join(5_000)
        assertEquals(SessionState.Closed, s.state.value)
        // Entradas tardías tras cerrar: no-op, nunca un uso tras liberar.
        s.setTouchButtons(0xFF); s.setPhysicalButtons(0x01); s.detachSurface()
    }

    @Test fun hammeringInputWhileClosingNeverCrashes() {
        repeat(30) {
            val s = parked()
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val workers = List(3) { n ->
                thread {
                    while (!stop.get()) {
                        s.setTouchButtons(n); s.setPhysicalButtons(n); s.detachSurface()
                        runCatching { s.saveState() }
                    }
                }
            }
            Thread.sleep(5)
            s.close()
            stop.set(true)
            workers.forEach { it.join(5_000) }
        }
    }
}
