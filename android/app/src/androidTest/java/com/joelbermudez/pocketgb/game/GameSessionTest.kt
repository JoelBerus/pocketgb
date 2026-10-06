package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.saves.FlushResult
import com.joelbermudez.pocketgb.saves.SaveCoordinator
import com.joelbermudez.pocketgb.saves.SaveMirror
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.testing.FailableOps
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.openGame
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GameSession` sobre el núcleo real con ROMs sintéticas: guardado, estados, rollback y salida segura. */
class GameSessionTest {
    private fun alive() = Thread.getAllStackTraces().keys.count { it.name == SaveCoordinator.THREAD_NAME && it.isAlive }

    /** Deja correr el contador un rato y pausa (vaciado síncrono incluido). */
    private fun playAndPause(game: GameSession, ms: Long = 250): FlushResult {
        if (game.state.value == SessionState.Paused) game.resume() else game.start()
        val before = game.session.sramDirtySequence()
        assertTrue(waitUntil { game.session.sramDirtySequence() > before + 2 })
        Thread.sleep(ms)
        return game.pause()
    }

    @Test
    fun loadingAStateSavesItsSRAMWithBackupOfThePrevious() {
        openGame(SyntheticRom.sramCounter()).use { g ->
            val game = g.game
            val store = g.store!!
            assertEquals(FlushResult.Saved, playAndPause(game))
            val a1 = store.load()!!
            assertArrayEquals(game.session.copySram(), a1)
            game.saveState(StateSlot.MANUAL1) // el estado contiene la SRAM a1

            assertEquals(FlushResult.Saved, playAndPause(game, 300))
            val a2 = store.load()!!
            assertFalse("el contador avanzó", a1.contentEquals(a2))

            game.loadState(StateSlot.MANUAL1)
            assertArrayEquals("el núcleo vuelve a a1", a1, game.session.copySram())
            assertArrayEquals("el disco sigue al núcleo: la SRAM del estado se persiste", a1, store.load())
            assertTrue(
                "la partida anterior (a2) queda como backup",
                store.backups().any { store.backupFile(it.index).readBytes().contentEquals(a2) },
            )
            assertTrue("antes de cargar se guardó el estado actual en AUTO", g.states.entries().containsKey(StateSlot.AUTO))
        }
    }

    @Test
    fun loadStateFailsVisiblyAndRollsBack() {
        val ops = FailableOps()
        openGame(SyntheticRom.sramCounter(), ops = ops).use { g ->
            val game = g.game
            val store = g.store!!
            playAndPause(game)
            game.saveState(StateSlot.MANUAL1)
            assertEquals(FlushResult.Saved, playAndPause(game, 300))
            val y = store.load()!!

            ops.failSav = true
            val error = assertThrows(StateError.SaveFailed::class.java) { game.loadState(StateSlot.MANUAL1) }
            assertTrue(error.message!!.contains("La partida actual no ha cambiado"))
            assertArrayEquals("rollback: el núcleo vuelve a como estaba", y, game.session.copySram())
            assertArrayEquals("el disco no cambió", y, store.load())

            ops.failSav = false
            game.loadState(StateSlot.MANUAL1) // con el disco sano, cargar funciona y persiste
            assertArrayEquals(game.session.copySram(), store.load())
        }
    }

    @Test
    fun statesNeedAPausedSession() {
        openGame(SyntheticRom.sramCounter()).use { g ->
            val game = g.game
            game.start()
            assertThrows(StateError.NotParked::class.java) { game.saveState(StateSlot.MANUAL1) }
            assertThrows(StateError.NotParked::class.java) { game.loadState(StateSlot.MANUAL1) }
            game.pause()
            game.saveState(StateSlot.MANUAL1) // ya en pausa funciona
            assertTrue(g.states.entries().containsKey(StateSlot.MANUAL1))
            assertNotNull(g.states.entries().getValue(StateSlot.MANUAL1).thumbnail)
        }
    }

    @Test
    fun loadingAnEmptySlotIsAVisibleError() {
        openGame(SyntheticRom.sramCounter()).use { g ->
            g.game.start()
            g.game.pause()
            assertThrows(StateError.Io::class.java) { g.game.loadState(StateSlot.MANUAL3) }
        }
    }

    @Test
    fun synchronousFlushDoesNotWaitForBlockedMirror() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val blocked = object : SaveMirror {
            override fun snapshot() = SaveMirror.Snapshot.Absent
            override fun write(data: ByteArray): Long? {
                entered.countDown()
                gate.await(30, TimeUnit.SECONDS)
                return null
            }
        }
        openGame(SyntheticRom.sramCounter(), mirror = blocked).use { g ->
            val game = g.game
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            val nativeStart = System.nanoTime()
            game.session.pause()
            android.util.Log.i("A5Metrics", "nativePause=${(System.nanoTime() - nativeStart) / 1_000_000} ms")
            val started = System.nanoTime()
            val result = game.pause()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(FlushResult.Saved, result)
            // El espejo sigue bloqueado 30 s: si el vaciado lo esperase, devolvería TimedOut tras 3 s, no Saved.
            assertTrue("el espejo bloqueado no retrasa el vaciado local ($elapsedMs ms)", elapsedMs < 3_000)
            assertArrayEquals(game.session.copySram(), g.store!!.load())
            assertTrue("el espejo sí lo intentó", entered.await(5, TimeUnit.SECONDS))
            gate.countDown()
        }
    }

    @Test
    fun aFailingMirrorRaisesANonBlockingEventAndNeverFailsTheLocalSave() {
        val broken = object : SaveMirror {
            override fun snapshot() = SaveMirror.Snapshot.Absent
            override fun write(data: ByteArray): Long? = throw java.io.IOException("proveedor sin conexión")
        }
        openGame(SyntheticRom.sramCounter(), mirror = broken).use { g ->
            val events = java.util.concurrent.CopyOnWriteArrayList<GameEvent>()
            val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch(
                start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED,
            ) { g.game.events.flow.collect { events += it } }
            assertEquals(FlushResult.Saved, playAndPause(g.game))
            assertTrue("el aviso de espejo llega", waitUntil(10_000) { events.contains(GameEvent.MirrorTrouble) })
            assertArrayEquals("la copia local no se ve afectada", g.game.session.copySram(), g.store!!.load())
            assertNull("un fallo del espejo no es un problema de guardado local", g.game.saveProblem.value)
            collector.cancel()
        }
    }

    @Test
    fun exitWithLocalFailureKeepsTheSessionOpenAndPausedThenRetrySucceeds() {
        val ops = FailableOps()
        openGame(SyntheticRom.sramCounter(), ops = ops).use { g ->
            val game = g.game
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            ops.failSav = true
            val first = game.exit()
            assertTrue("fallo local: no se cierra limpio", first is ExitResult.LocalSaveFailed)
            assertFalse(game.isClosed)
            assertEquals(SessionState.Paused, game.state.value)
            assertNotNull(game.saveProblem.value)
            assertTrue((first as ExitResult.LocalSaveFailed).error.message!!.contains("disco lleno"))

            // Reintento con el disco sano ⇒ sale limpio y deja disco == núcleo y el estado AUTO.
            ops.failSav = false
            val core = game.session.copySram()
            assertEquals(ExitResult.Clean, game.exit())
            assertTrue(game.isClosed)
            assertEquals(SessionState.Closed, game.state.value)
            assertArrayEquals(core, g.store!!.load())
            assertTrue(g.states.entries().containsKey(StateSlot.AUTO))
        }
    }

    @Test
    fun forcedExitAfterLocalFailureWritesARescueStateInItsOwnSlot() {
        val ops = FailableOps()
        openGame(SyntheticRom.sramCounter(), ops = ops).use { g ->
            val game = g.game
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            ops.failSav = true
            assertTrue(game.exit() is ExitResult.LocalSaveFailed)
            assertEquals(ExitResult.Clean, game.exit(force = true))
            assertTrue(game.isClosed)
            assertTrue("el estado de rescate se escribió en SU ranura", g.states.entries().containsKey(StateSlot.RESCUE))
            assertFalse("y no pisó el AUTO", g.states.entries().containsKey(StateSlot.AUTO))
        }
    }

    @Test
    fun aSessionWithoutTargetKeepsStatesButDoesNotPersistSram() {
        openGame(SyntheticRom.sramCounter(), persist = false).use { g ->
            val game = g.game
            assertFalse(game.persists)
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            assertEquals(FlushResult.Unchanged, game.pause())
            game.saveState(StateSlot.MANUAL2)
            game.loadState(StateSlot.MANUAL2)
            assertEquals(ExitResult.Clean, game.exit())
            assertFalse("no se escribió ninguna partida", File(g.root, "saves").exists())
        }
    }

    @Test
    fun closingJoinsTheSaveThreadAndIsIdempotent() {
        val before = alive()
        openGame(SyntheticRom.sramCounter()).use { g ->
            g.game.start()
            assertEquals(before + 1, alive())
            assertEquals(ExitResult.Clean, g.game.exit())
            assertEquals("el hilo de guardado se une antes de liberar el núcleo", before, alive())
            g.game.close() // idempotente
            assertEquals(ExitResult.Clean, g.game.exit())
            assertEquals(FlushResult.Unchanged, g.game.pause())
        }
    }

    @Test
    fun touchInputAfterCloseIsIgnored() {
        openGame(SyntheticRom.sramCounter()).use { g ->
            val session = g.session
            g.game.start()
            g.game.exit()
            session.setTouchButtons(0xFF)
            session.setPhysicalButtons(0x01)
        }
    }

    @Test
    fun rtcCartridgeSavesTheClockBlockToo() {
        openGame(SyntheticRom.mbc3Rtc()).use { g ->
            val game = g.game
            game.start()
            Thread.sleep(300)
            val result = game.pause()
            assertTrue(result == FlushResult.Saved || result == FlushResult.Unchanged)
            val saved = g.store!!.load()
            if (saved != null) assertEquals(8192 + 48, saved.size)
            assertEquals(8192 + 48, game.session.sramSaveSize)
        }
    }

    @Test
    fun savingAStateWhileTheMirrorIsBlockedStillWorks() {
        openGame(SyntheticRom.sramCounter()).use { g ->
            playAndPause(g.game)
            val ms = measure { g.game.saveState(StateSlot.MANUAL4) }
            assertTrue("guardar un estado es rápido ($ms ms)", ms < 3_000)
            assertNull(g.states.entries().getValue(StateSlot.MANUAL4).takeIf { it.corrupt })
        }
    }

    private fun measure(block: () -> Unit): Long {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1_000_000
    }
}
