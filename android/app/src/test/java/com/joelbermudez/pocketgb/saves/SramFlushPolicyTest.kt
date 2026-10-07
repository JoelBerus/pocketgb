package com.joelbermudez.pocketgb.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SramFlushPolicyTest {
    private var now = 10_000L
    private fun policy() = SramFlushPolicy(clock = { now })

    @Test fun nothingDirtyAndNothingElapsedDoesNotFlush() {
        val p = policy()
        assertFalse(p.poll(dirty = false))
        now += 999
        assertFalse(p.poll(dirty = false))
    }

    @Test fun dirtyFlushesAfterTheOneSecondDebounce() {
        val p = policy()
        assertFalse(p.poll(dirty = true)) // acaba de guardar el juego
        now += 999
        assertFalse(p.poll(dirty = false))
        now += 1
        assertTrue(p.poll(dirty = false))
        // Ya se pidió el flush: no se repite en el siguiente sondeo.
        assertFalse(p.poll(dirty = false))
    }

    @Test fun eachNewDirtyRestartsTheDebounce() {
        val p = policy()
        p.poll(dirty = true)
        now += 800
        assertFalse(p.poll(dirty = true)) // el juego vuelve a guardar: la espera empieza de nuevo
        now += 800
        assertFalse(p.poll(dirty = false))
        now += 200
        assertTrue(p.poll(dirty = false))
    }

    @Test fun safetyNetFlushesAfterSixtySecondsWithoutDirtyFlag() {
        val p = policy()
        now += 59_999
        assertFalse(p.poll(dirty = false))
        now += 1
        assertTrue(p.poll(dirty = false))
        assertFalse(p.poll(dirty = false))
        now += 60_000
        assertTrue(p.poll(dirty = false))
    }

    @Test fun failureReschedulesAfterTheDebounce() {
        val p = policy()
        p.poll(dirty = true)
        now += 1_000
        assertTrue(p.poll(dirty = false))
        p.onFlushFailed()
        assertFalse(p.poll(dirty = false))
        now += 999
        assertFalse(p.poll(dirty = false))
        now += 1
        assertTrue("el reintento llega al segundo del fallo", p.poll(dirty = false))
    }

    @Test fun repeatedFailuresKeepRetryingEverySecond() {
        val p = policy()
        p.poll(dirty = true)
        now += 1_000
        var retries = 0
        repeat(5) {
            if (p.poll(dirty = false)) { retries++; p.onFlushFailed() }
            now += 1_000
        }
        assertEquals(5, retries)
    }

    @Test fun aSynchronousFlushResetsTheTimers() {
        val p = policy()
        p.poll(dirty = true)
        now += 500
        p.onFlushDone() // flush síncrono (pausa/salida) por otra vía
        now += 1_000
        assertFalse(p.poll(dirty = false))
        now += 59_000
        assertTrue(p.poll(dirty = false)) // la red cuenta desde el flush síncrono
    }
}
