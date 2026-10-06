package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.emulator.CoreError
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.SessionError
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.CloseResult
import com.joelbermudez.pocketgb.saves.FlushResult
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.testing.FailableOps
import com.joelbermudez.pocketgb.testing.StallingOps
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.openGame
import com.joelbermudez.pocketgb.testing.waitUntil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Correcciones de la 1ª vuelta de auditoría de A5 sobre `GameSession` (núcleo real, ROM sintética). */
class GameSessionHardeningTest {
    private fun playAndPause(game: GameSession, ms: Long = 250): FlushResult {
        if (game.state.value == SessionState.Paused) game.resume() else game.start()
        val before = game.session.sramDirtySequence()
        assertTrue(waitUntil { game.session.sramDirtySequence() > before + 2 })
        Thread.sleep(ms)
        return game.pause()
    }

    /** Sesión cuyo segundo `loadStateRaw` (el rollback) falla. */
    private class RollbackFailsSession : EmulatorSession() {
        @Volatile var loads = 0
        override fun loadStateRaw(data: ByteArray) {
            if (++loads == 2) throw CoreError.StateCorrupt()
            super.loadStateRaw(data)
        }
    }

    /** Sesión cuya primera pausa nativa dice que ya estaba en pausa (carrera de `InvalidTransition`). */
    class PauseRacesSession : EmulatorSession() {
        @Volatile var fail = true
        override fun pause() {
            if (fail) {
                fail = false
                throw SessionError.InvalidTransition("pausar", SessionState.Paused)
            }
            super.pause()
        }
    }

    // ---- Codex H2 / Opus H5: rollback transaccional

    @Test
    fun aTimedOutStateLoadRollsBackAndTheDiskEndsAtThePreviousSaveBeforeAnyNextTick() {
        val ops = StallingOps()
        openGame(SyntheticRom.sramCounter(), ops = ops, autoTick = false, flushTimeoutMs = 1_500).use { g ->
            val game = g.game
            val store = g.store!!
            assertEquals(FlushResult.Saved, playAndPause(game))
            game.saveState(StateSlot.MANUAL1) // el estado contiene la SRAM a1
            assertEquals(FlushResult.Saved, playAndPause(game, 300))
            val a2 = store.load()!!

            ops.arm() // la escritura de la SRAM del estado cargado (B) se atasca
            val error = assertThrows(StateError.SavePending::class.java) { game.loadState(StateSlot.MANUAL1) }
            assertFalse("no afirma que la partida no cambió: no está confirmado", error.message!!.contains("no ha cambiado"))
            assertTrue(error.message!!.contains("pendiente"))
            assertTrue(ops.awaitEntered())

            // Se libera la escritura rezagada de B; "se cierra/muere" antes del siguiente tick: solo se drena la cola.
            ops.release()
            game.states() // otra operación en el MISMO hilo: vuelve cuando todo lo encolado antes ya terminó
            assertArrayEquals("el núcleo volvió a a2", a2, game.session.copySram())
            assertArrayEquals("el disco quedó en a2, no en la SRAM del estado rechazado", a2, store.load())
        }
    }

    @Test
    fun aConfirmedRollbackStillSaysTheGameDidNotChange() {
        val ops = FailableOps()
        openGame(SyntheticRom.sramCounter(), ops = ops, autoTick = false).use { g ->
            val game = g.game
            playAndPause(game)
            game.saveState(StateSlot.MANUAL1)
            assertEquals(FlushResult.Saved, playAndPause(game, 300))
            val y = g.store!!.load()!!
            ops.failSav = true
            val error = assertThrows(StateError.SaveFailed::class.java) { game.loadState(StateSlot.MANUAL1) }
            assertTrue(error.message!!.contains("no ha cambiado"))
            assertArrayEquals(y, g.store!!.load())
            assertNull("la barrera confirmó que disco == núcleo: ya no hay problema pendiente", game.saveProblem.value)
        }
    }

    @Test
    fun ifTheRollbackItselfFailsTheSessionStopsPersistingAndNeverWritesTheDoubtfulSram() {
        val ops = FailableOps()
        val session = RollbackFailsSession()
        openGame(SyntheticRom.sramCounter(), ops = ops, session = session, autoTick = false).use { g ->
            val game = g.game
            playAndPause(game)
            game.saveState(StateSlot.MANUAL1)
            assertEquals(FlushResult.Saved, playAndPause(game, 300))
            val y = g.store!!.load()!!
            ops.failSav = true

            assertThrows(StateError.RollbackFailed::class.java) { game.loadState(StateSlot.MANUAL1) }
            assertFalse("la sesión ya no persiste", game.persists)
            assertNotNull("y lo muestra como problema de guardado", game.saveProblem.value)

            ops.failSav = false // aunque el disco vuelva: ya no se escribe nada
            assertEquals(FlushResult.Unchanged, game.pause())
            assertArrayEquals("la partida anterior sigue intacta", y, g.store!!.load())
            assertEquals(ExitResult.Clean, game.exit())
            assertArrayEquals(y, g.store!!.load())
        }
    }

    // ---- Codex H1 / I3: cierre con el hilo de guardado atascado

    @Test
    fun theNativeHandleIsNeverReleasedWhileTheSaveThreadIsStillAlive() {
        val ops = StallingOps()
        openGame(SyntheticRom.sramCounter(), ops = ops, autoTick = false, flushTimeoutMs = 300, closeGraceMs = 200, closeKillWaitMs = 200).use { g ->
            val game = g.game
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            ops.arm()
            assertEquals(FlushResult.TimedOut, game.pause()) // la escritura se atasca y no responde a interrupciones
            assertTrue(ops.awaitEntered())

            val result = game.tryClose()

            assertTrue("el cierre lo reporta con un fallo tipado: $result", result is CloseResult.SaveThreadStuck)
            assertTrue(game.isClosed)
            assertEquals("el handle NO se liberó con el hilo vivo", SessionState.Paused, game.state.value)
            assertTrue("el handle sigue siendo válido", game.session.frameCount >= 0)

            ops.release()
            assertTrue("al salir el hilo, el reaper libera el handle", waitUntil(10_000) { game.state.value == SessionState.Closed })
        }
    }

    // ---- Anti-ANR: tras un plazo agotado, el hilo principal espera menos

    @Test
    fun afterATimeoutTheMainThreadFlushBudgetShrinks() {
        val ops = StallingOps()
        openGame(SyntheticRom.sramCounter(), ops = ops, autoTick = false, flushTimeoutMs = 1_500).use { g ->
            val game = g.game
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            ops.arm()
            val first = System.nanoTime()
            assertEquals(FlushResult.TimedOut, game.pause())
            val firstMs = (System.nanoTime() - first) / 1_000_000
            assertTrue("el primer plazo es el completo ($firstMs ms)", firstMs >= 1_400)

            val second = System.nanoTime()
            assertEquals(FlushResult.TimedOut, game.pause()) // p. ej. ON_STOP justo después de ON_PAUSE
            val secondMs = (System.nanoTime() - second) / 1_000_000
            assertTrue("el segundo es corto ($secondMs ms)", secondMs < 1_200)
            ops.release()
        }
    }

    // ---- Opus H6: rescate J6 en su ranura

    @Test
    fun forcedExitsNeverOverwriteAnEarlierRescueState() {
        val ops = FailableOps()
        openGame(SyntheticRom.sramCounter(), ops = ops, autoTick = false).use { g ->
            val game = g.game
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            ops.failSav = true
            assertTrue(game.exit() is ExitResult.LocalSaveFailed)
            // Primer rescate manual (segunda salida forzada tras reanudar y volver a pausar).
            assertEquals(ExitResult.Clean, game.exit(force = true))
            val first = g.states.load(StateSlot.RESCUE)
            // Una sesión nueva sobre los mismos estados y otro rescate: el primero se conserva aparte.
            g.states.saveRescue(ByteArray(first.size) { 1 }, null)
            val archived = g.states.directory.listFiles { f -> f.name.startsWith("rescue-") && f.name.endsWith(".state") }!!
            assertEquals(1, archived.size)
            assertArrayEquals(first, archived.single().readBytes())
        }
    }

    // ---- Opus H2 / DeepSeek H1: onCleared ya no fuerza nada; rescueExit

    @Test
    fun rescueExitWithAFailingDiskKeepsTheSessionOpenWithARescueStateAndRetriesDoNotPileUpRescues() {
        val ops = FailableOps()
        openGame(SyntheticRom.sramCounter(), ops = ops, autoTick = false).use { g ->
            val game = g.game
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            game.session.pause()
            ops.failSav = true

            assertEquals(RescueOutcome.KeptOpen, game.rescueExit(attempts = 2, retryDelayMs = 20))
            assertFalse("no se cierra con un guardado sin confirmar", game.isClosed)
            assertTrue("estado de rescate escrito", g.states.entries().containsKey(StateSlot.RESCUE))
            assertNotNull(game.saveProblem.value)

            // Los reintentos automáticos del registro no reescriben el estado de rescate (no se acumulan archivados).
            repeat(3) { assertEquals(RescueOutcome.KeptOpen, game.rescueExit(attempts = 1, retryDelayMs = 0, writeRescueState = false)) }
            assertEquals(0, g.states.directory.listFiles { f -> f.name.startsWith("rescue-") }!!.size)
        }
    }

    /**
     * A5V2-H1 (nivel sesión + registro, sin segunda llamada manual): el rescate acaba en KeptOpen, la sesión pasa al
     * registro, el disco vuelve y el REGISTRO guarda AUTO, cierra la sesión y libera la huella.
     */
    @Test
    fun theOrphanRegistryClosesAKeptOpenSessionByItselfWhenTheDiskRecoversAndReleasesTheFingerprint() {
        val ops = FailableOps()
        val ownership = FingerprintOwnership()
        val registry = OrphanSessionRegistry(initialDelayMs = 30, maxDelayMs = 100, keepAliveMs = 100)
        openGame(SyntheticRom.sramCounter(), ops = ops, autoTick = false, ownership = ownership).use { g ->
            val game = g.game
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            game.session.pause()
            ops.failSav = true

            registry.claim(game)
            assertTrue(ownership.isOwned(game.fingerprint))
            registry.settle(game, game.rescueExit(attempts = 2, retryDelayMs = 20)) // KeptOpen
            Thread.sleep(300) // varios reintentos con el disco fallando: sigue abierta y bloqueada
            assertFalse(game.isClosed)
            assertTrue(ownership.isOwned(game.fingerprint))

            ops.failSav = false
            val core = game.session.copySram()
            assertTrue("el registro la cierra solo", waitUntil(15_000) { game.isClosed })
            assertTrue("y libera la huella", waitUntil(15_000) { !ownership.isOwned(game.fingerprint) })
            assertArrayEquals("el disco quedó con la SRAM confirmada", core, g.store!!.load())
            assertTrue(g.states.entries().containsKey(StateSlot.AUTO))
            assertTrue("sin hilos del registro vivos", waitUntil(10_000) { registry.liveThreads == 0 })
        }
    }

    // ---- A5V2-H3: rollback fallido con una escritura rezagada

    /**
     * B (la SRAM del estado rechazado) queda en vuelo, el rollback del núcleo falla y el plazo vence. Mientras B no
     * termine la huella está bloqueada y el mensaje NO afirma que la partida siga intacta; al liberarse B, la
     * reparación (en el mismo hilo, detrás de B) deja A como principal y B como backup, y la huella se libera.
     */
    @Test
    fun aFailedRollbackWithALateWriteEndsWithThePreviousSaveAsPrimaryAndBlocksReopeningUntilThen() {
        val ops = StallingOps()
        val session = RollbackFailsSession()
        val ownership = FingerprintOwnership()
        openGame(
            SyntheticRom.sramCounter(), ops = ops, session = session, autoTick = false, flushTimeoutMs = 400,
            ownership = ownership, repairWaitMs = 300,
        ).use { g ->
            val game = g.game
            val store = g.store!!
            assertEquals(FlushResult.Saved, playAndPause(game))
            game.saveState(StateSlot.MANUAL1)
            assertEquals(FlushResult.Saved, playAndPause(game, 300))
            val a = store.load()!!

            ops.arm() // B (la SRAM del estado cargado) se atasca en vuelo
            val error = assertThrows(StateError.RollbackFailed::class.java) { game.loadState(StateSlot.MANUAL1) }
            assertTrue(ops.awaitEntered())
            assertFalse("no estaba confirmado", error.restored)
            assertFalse("no afirma que siga intacta", error.message!!.contains("intacta"))
            assertTrue("la huella está bloqueada hasta resolverlo", ownership.isOwned(game.fingerprint))
            val launcher = GameplayTestHost.launcher(g.root, ops, ownership = ownership)
            val reopen = launcher.openBlocking(GameplayTestHost.entry)
            assertEquals(OpenResult.Failed(OpenError.SavePending), reopen)

            ops.release() // B termina tarde y se instala como principal; la reparación la sucede
            assertTrue("la reparación termina", waitUntil(15_000) { store.load()?.contentEquals(a) == true })
            assertArrayEquals("el principal es A, no B", a, store.load())
            assertTrue("B quedó como backup, no se perdió", store.backups().isNotEmpty())
            assertTrue("la sesión sigue abierta y conserva su huella", ownership.isOwned(game.fingerprint))

            // La propiedad la suelta el cierre de la sesión (y no antes): ahora sí se puede reabrir.
            game.close()
            assertTrue("el cierre libera la huella", waitUntil(15_000) { !ownership.isOwned(game.fingerprint) })
            val reopened = launcher.openBlocking(GameplayTestHost.entry)
            assertTrue("tras resolverlo se puede reabrir: $reopened", reopened is OpenResult.Opened)
            (reopened as OpenResult.Opened).game.close()
        }
    }

    /**
     * A5V3-H1: si la sesión se cierra MIENTRAS la reparación sigue pendiente, el lease no se suelta hasta que la
     * reparación termina (la reparación es un segundo "uso" del mismo token).
     */
    @Test
    fun theLeaseOutlivesTheSessionCloseUntilThePendingRepairFinishes() {
        val ops = StallingOps()
        val session = RollbackFailsSession()
        val ownership = FingerprintOwnership()
        openGame(
            SyntheticRom.sramCounter(), ops = ops, session = session, autoTick = false, flushTimeoutMs = 400,
            closeGraceMs = 200, closeKillWaitMs = 200, ownership = ownership, repairWaitMs = 300,
        ).use { g ->
            val game = g.game
            val store = g.store!!
            assertEquals(FlushResult.Saved, playAndPause(game))
            game.saveState(StateSlot.MANUAL1)
            assertEquals(FlushResult.Saved, playAndPause(game, 300))
            val a = store.load()!!
            ops.arm()
            assertThrows(StateError.RollbackFailed::class.java) { game.loadState(StateSlot.MANUAL1) }
            assertTrue(ops.awaitEntered())

            game.tryClose() // hilo atascado: SaveThreadStuck, y además hay una reparación pendiente
            assertTrue("la huella sigue con dueño", ownership.isOwned(game.fingerprint))
            assertNull("nadie más puede adquirirla", ownership.tryAcquire(game.fingerprint, "intruso"))

            ops.release()
            assertTrue("al terminar todo se libera", waitUntil(15_000) { !ownership.isOwned(game.fingerprint) })
            assertArrayEquals("y el principal es A", a, store.load())
        }
    }

    // ---- DeepSeek H1 / A5V3: el reaper suelta el lease también ante interrupción

    @Test
    fun theSaveReaperReleasesTheLeaseEvenIfItIsInterrupted() {
        val ops = StallingOps()
        val ownership = FingerprintOwnership()
        openGame(
            SyntheticRom.sramCounter(), ops = ops, autoTick = false, flushTimeoutMs = 300,
            closeGraceMs = 200, closeKillWaitMs = 200, ownership = ownership,
        ).use { g ->
            val game = g.game
            game.start()
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            ops.arm()
            assertEquals(FlushResult.TimedOut, game.pause())
            assertTrue(ops.awaitEntered())
            assertTrue(game.tryClose() is CloseResult.SaveThreadStuck)
            assertTrue("con el hilo vivo la huella sigue con dueño", ownership.isOwned(game.fingerprint))

            val reaper = Thread.getAllStackTraces().keys.first { it.name == "pocketgb-save-reaper" && it.isAlive }
            reaper.interrupt()
            assertTrue("interrumpido, el reaper suelta el lease", waitUntil(10_000) { !ownership.isOwned(game.fingerprint) })
            assertFalse(game.holdsLease)

            ops.release()
            game.awaitSaveThreadExit()
            try { game.session.close() } catch (_: Exception) {}
        }
    }

    // ---- Opus H2: onBackground con InvalidTransition

    @Test
    fun backgroundingWhenTheNativePauseRacesNeverCrashes() {
        val session = PauseRacesSession()
        openGame(SyntheticRom.sramCounter(), session = session, autoTick = false).use { g ->
            val vm = GameplayViewModel(
                GameLauncher(
                    roms = com.joelbermudez.pocketgb.library.RomSource { _, _ -> SyntheticRom.sramCounter() },
                    savesDirectory = java.io.File(g.root, "saves"),
                    statesRoot = java.io.File(g.root, "states"),
                ),
            )
            g.game.start()
            vm.adopt(g.game)
            vm.onBackground() // la primera pausa lanza InvalidTransition: se traga
            vm.onBackground() // la segunda sí pausa
            assertEquals(SessionState.Paused, g.game.state.value)
        }
    }

    @Test
    fun backgroundingWhileExitingNeverCrashesAndEndsClosed() {
        repeat(5) { round ->
            openGame(SyntheticRom.sramCounter(), autoTick = false).use { g ->
                val vm = GameplayViewModel(
                    GameLauncher(
                        roms = com.joelbermudez.pocketgb.library.RomSource { _, _ -> SyntheticRom.sramCounter() },
                        savesDirectory = java.io.File(g.root, "saves"),
                        statesRoot = java.io.File(g.root, "states"),
                    ),
                )
                g.game.start()
                assertTrue(waitUntil { g.game.session.sramDirtySequence() > 3 })
                vm.adopt(g.game)
                val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
                val go = java.util.concurrent.CountDownLatch(1)
                val exiting = kotlin.concurrent.thread { go.await(); runCatching { g.game.exit() }.onFailure(failures::add) }
                val background = kotlin.concurrent.thread { go.await(); repeat(20) { runCatching { vm.onBackground() }.onFailure(failures::add) } }
                go.countDown()
                exiting.join(30_000)
                background.join(30_000)
                assertTrue("ronda $round: sin excepciones ${failures.firstOrNull()}", failures.isEmpty())
                assertTrue("ronda $round: cerrada", waitUntil { g.game.isClosed })
            }
        }
    }
}
