package com.joelbermudez.pocketgb.game

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.emulator.GbModel
import com.joelbermudez.pocketgb.emulator.EmulationOptions
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomSource
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.FlushResult
import com.joelbermudez.pocketgb.saves.LaunchMode
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
import com.joelbermudez.pocketgb.saves.ResumeFailure
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.emulator.CoreError
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.testing.FailableOps
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.openGame
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import org.junit.Assert.assertThrows
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A9 · Continuación exacta de extremo a extremo con el núcleo real y la ROM contador sintética (libre): salir guarda el
 * AUTO; «Continuar» lo retoma byte a byte; si el juego guardó después, si es de otro modelo o si está dañado, cae a la
 * partida sin escribir nada y deja abrir «desde el inicio» con la partida intacta.
 */
@RunWith(AndroidJUnit4::class)
class ExactContinuationTest {
    private lateinit var root: File
    private val opened = mutableListOf<GameSession>()
    private val ownership = FingerprintOwnership()
    private var rom: ByteArray = SyntheticRom.sramCounter()

    private val entry get() = RomEntry(
        id = "Contador.gb",
        uri = "content://a9/Contador.gb",
        fileName = "Contador.gb",
        title = "CONTADOR",
        isColor = false,
        sizeBytes = rom.size.toLong(),
        headerChecksumOk = true,
        problem = null,
    )

    private val launcher get() = GameLauncher(
        roms = RomSource { _, _ -> rom },
        savesDirectory = File(root, "saves"),
        statesRoot = File(root, "states"),
        registry = MirrorChannelRegistry(),
        ownership = ownership,
    )

    @Before
    fun setUp() {
        root = tempDir("a9-continue")
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        root.deleteRecursively()
    }

    private fun open(mode: LaunchMode, options: EmulationOptions? = null): OpenResult =
        launcher.openBlocking(entry, options, mode).also { if (it is OpenResult.Opened) opened += it.game }

    private fun openFresh(options: EmulationOptions? = null) = open(LaunchMode.FRESH, options) as OpenResult.Opened

    /** Juega hasta que la partida cambie varias veces y sale: la salida vacía la SRAM y guarda el AUTO. */
    private fun playAndExit(game: GameSession) {
        game.start()
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        assertEquals(ExitResult.Clean, game.exit())
    }

    private fun saves(fingerprint: String) = SaveStore(File(root, "saves"), fingerprint)
    private fun states(fingerprint: String) = StateStore(File(root, "states"), fingerprint)

    /** Todo lo que hay en disco de la partida (principal y backups) para comprobar que nada cambia. */
    private fun savesOnDisk(): Map<String, List<Byte>> =
        File(root, "saves").walkTopDown().filter { it.isFile && it.name.endsWith(".sav") }
            .associate { it.relativeTo(root).path to it.readBytes().toList() }

    private fun noSaveThreadAlive() = Thread.getAllStackTraces().keys.none { it.name == "pocketgb-saves" && it.isAlive }

    @Test
    fun continueResumesTheExactAutomaticStateWithoutWritingTheSave() {
        val first = openFresh().game
        val fingerprint = first.fingerprint
        playAndExit(first)
        val auto = states(fingerprint).load(StateSlot.AUTO)
        val before = savesOnDisk()
        assertTrue("hay partida en disco", before.isNotEmpty())

        val resumed = open(LaunchMode.RESUME)
        assertTrue("«Continuar» abre: $resumed", resumed is OpenResult.Opened)
        resumed as OpenResult.Opened
        assertTrue(resumed.resumed)
        // El núcleo está exactamente en el estado guardado al salir (mismo ciclo, misma RAM, mismo todo).
        assertArrayEquals(auto, resumed.game.session.saveStateParked())
        assertEquals("abrir no escribe la partida", before, savesOnDisk())
        resumed.game.close()

        // «Jugar desde el inicio» abre la misma partida sin el estado: el núcleo arranca de cero.
        val fresh = openFresh()
        assertFalse(fresh.resumed)
        assertFalse(auto.contentEquals(fresh.game.session.saveStateParked()))
        assertArrayEquals(saves(fingerprint).load(), fresh.game.session.copySram())
    }

    @Test
    fun aSaveWrittenAfterTheAutomaticStateInvalidatesItAndNothingIsLost() {
        val first = openFresh().game
        val fingerprint = first.fingerprint
        playAndExit(first)
        val auto = states(fingerprint).load(StateSlot.AUTO)
        // El juego guardó después del AUTO (otra sesión, otro equipo o un backup restaurado): partida más nueva.
        val newer = ByteArray(8192) { 0x5A }
        saves(fingerprint).save(newer)
        saves(fingerprint).saveFile.setLastModified(System.currentTimeMillis() + 60_000)
        val before = savesOnDisk()

        val result = open(LaunchMode.RESUME)
        assertEquals(OpenResult.Failed(OpenError.ResumeFailed(ResumeFailure.NOT_CURRENT, fingerprint)), result)
        assertEquals("la partida más nueva queda intacta", before, savesOnDisk())
        assertTrue("sin hilo de guardado ni sesión colgada", noSaveThreadAlive())
        assertFalse("el AUTO obsoleto se retira…", states(fingerprint).stateFile(StateSlot.AUTO).exists())
        assertArrayEquals("…apartado, no borrado", auto, states(fingerprint).obsoleteAutoFiles().single().readBytes())

        // La huella quedó libre: «Jugar desde el inicio» abre con la partida más nueva.
        val fresh = openFresh()
        assertArrayEquals(newer, fresh.game.session.copySram())
    }

    @Test
    fun anAutomaticStateWhoseRamDiffersFromTheSaveIsRejectedEvenWithValidDates() {
        val first = openFresh().game
        val fingerprint = first.fingerprint
        playAndExit(first)
        // Otro contenido con la fecha más vieja que el AUTO (un reloj atrasado, un espejo con la misma hora): solo la
        // comprobación por contenido lo detecta.
        val other = ByteArray(8192) { 0x33 }
        saves(fingerprint).save(other)
        saves(fingerprint).saveFile.setLastModified(states(fingerprint).stateFile(StateSlot.AUTO).lastModified() - 60_000)
        val before = savesOnDisk()

        val result = open(LaunchMode.RESUME)
        assertEquals(OpenResult.Failed(OpenError.ResumeFailed(ResumeFailure.NOT_CURRENT, fingerprint)), result)
        assertEquals(before, savesOnDisk())
        assertArrayEquals(other, openFresh().game.session.copySram())
    }

    @Test
    fun anAutomaticStateOfAnotherModelIsRejectedAndKept() {
        val first = openFresh(EmulationOptions(model = GbModel.DMG)).game
        val fingerprint = first.fingerprint
        playAndExit(first)
        val before = savesOnDisk()
        // El usuario activó el color: la misma ROM se abre ahora en compatibilidad CGB.
        val result = open(LaunchMode.RESUME, EmulationOptions(model = GbModel.CGB))
        assertEquals(OpenResult.Failed(OpenError.ResumeFailed(ResumeFailure.INCOMPATIBLE, fingerprint)), result)
        assertEquals(before, savesOnDisk())
        assertTrue("se conserva para cuando vuelva el modelo anterior", states(fingerprint).stateFile(StateSlot.AUTO).exists())
        // Con el modelo anterior vuelve a valer.
        assertTrue(open(LaunchMode.RESUME, EmulationOptions(model = GbModel.DMG)) is OpenResult.Opened)
    }

    @Test
    fun aCorruptOrTruncatedAutomaticStateFallsBackWithoutLoss() {
        val first = openFresh().game
        val fingerprint = first.fingerprint
        playAndExit(first)
        val file = states(fingerprint).stateFile(StateSlot.AUTO)
        val auto = file.readBytes()
        val stamp = file.lastModified()
        val before = savesOnDisk()

        file.writeBytes(auto.copyOf(auto.size / 2))
        file.setLastModified(stamp)
        assertEquals(
            OpenResult.Failed(OpenError.ResumeFailed(ResumeFailure.CORRUPT, fingerprint)),
            open(LaunchMode.RESUME),
        )
        file.writeBytes(auto.copyOf().also { it[auto.size / 2] = (it[auto.size / 2] + 1).toByte() }) // CRC roto
        file.setLastModified(stamp)
        assertEquals(
            OpenResult.Failed(OpenError.ResumeFailed(ResumeFailure.CORRUPT, fingerprint)),
            open(LaunchMode.RESUME),
        )
        assertEquals(before, savesOnDisk())
        assertTrue(noSaveThreadAlive())
        assertTrue(openFresh().game.session.copySram().contentEquals(saves(fingerprint).load()))
    }

    @Test
    fun withoutAnAutomaticStateContinueReportsItAndPlayStillWorks() {
        val result = open(LaunchMode.RESUME)
        assertTrue(result is OpenResult.Failed)
        assertEquals(ResumeFailure.MISSING, ((result as OpenResult.Failed).error as OpenError.ResumeFailed).reason)
        assertTrue(noSaveThreadAlive())
        assertNotNull(openFresh())
    }

    @Test
    fun anRtcCartridgeResumesEvenIfOnlyTheClockFooterDiffers() {
        rom = SyntheticRom.mbc3Rtc(ram = true)
        val first = openFresh().game
        first.start()
        Thread.sleep(300)
        assertEquals(ExitResult.Clean, first.exit())
        val before = savesOnDisk()
        val resumed = open(LaunchMode.RESUME)
        assertTrue("$resumed", resumed is OpenResult.Opened && resumed.resumed)
        assertEquals(before, savesOnDisk())
    }

    @Test
    fun theBackgroundAutomaticStateIsWrittenAfterTheSaveOnlyWhileParked() {
        val game = openFresh().game
        val fingerprint = game.fingerprint
        game.start()
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        assertFalse("corriendo no se guarda nada", game.saveAutoStateIfParked())
        assertFalse(states(fingerprint).stateFile(StateSlot.AUTO).exists())
        val flushed = game.pause()
        assertTrue(flushed == FlushResult.Saved || flushed == FlushResult.Unchanged)
        assertTrue(game.saveAutoStateIfParked())
        val auto = states(fingerprint).stateFile(StateSlot.AUTO)
        assertTrue(auto.exists())
        assertTrue("el AUTO no es anterior a la partida", auto.lastModified() >= saves(fingerprint).saveFile.lastModified())
        // El proceso muere aquí (sin Salir): «Continuar» retoma justo esa posición.
        val expected = auto.readBytes()
        game.close()
        val resumed = open(LaunchMode.RESUME) as OpenResult.Opened
        assertArrayEquals(expected, resumed.game.session.saveStateParked())
    }

    @Test
    fun thePauseTitleUsesTheAliasAndOpeningNeverRenamesAnything() {
        val renamed = launcher.openBlocking(entry.copy(alias = "Mi contador"), mode = LaunchMode.FRESH) as OpenResult.Opened
        opened += renamed.game
        assertEquals("Mi contador", renamed.game.title)
        assertEquals("CONTADOR", renamed.game.info.title)
        renamed.game.close()
        val plain = openFresh()
        assertEquals("CONTADOR", plain.game.title)
    }

    @Test
    fun theViewModelExplainsAFailedContinueAndPlaysFromTheStartWithTheSave() {
        val first = openFresh().game
        val fingerprint = first.fingerprint
        playAndExit(first)
        val newer = ByteArray(8192) { 0x5A }
        saves(fingerprint).save(newer)
        saves(fingerprint).saveFile.setLastModified(System.currentTimeMillis() + 60_000)
        val vm = GameplayViewModel(launcher = launcher)
        try {
            vm.refreshContinuations(setOf(fingerprint))
            vm.open(entry, LaunchMode.RESUME)
            assertTrue(waitUntil(10_000) { vm.dialog.value is GameDialog.ResumeFailed })
            // A9-H5: cuando se ve el aviso, la apertura ya terminó; «Jugar desde el inicio» no se pierde.
            assertFalse(vm.opening.value)
            assertEquals(ResumeFailure.NOT_CURRENT, (vm.dialog.value as GameDialog.ResumeFailed).reason)
            assertTrue("deja de ofrecerse «Continuar»", waitUntil(5_000) { fingerprint !in vm.resumable.value })
            vm.playFromStartAfterResumeFailure()
            assertTrue(waitUntil(10_000) { vm.game.value != null })
            // El ViewModel ya arrancó el juego: la ROM contador solo cambia `$A000`, que parte de la partida más nueva.
            val sram = vm.game.value!!.session.copySram()
            assertArrayEquals(newer.copyOfRange(1, newer.size), sram.copyOfRange(1, sram.size))
            vm.exit()
            assertTrue(waitUntil(10_000) { vm.game.value == null })
            assertTrue("al salir hay AUTO nuevo y vuelve «Continuar»", waitUntil(5_000) { fingerprint in vm.resumable.value })
        } finally {
            vm.game.value?.close()
        }
    }

    // ---- Respuesta a la auditoría A9 ----

    /** A9-H2: con un `.sav` de tamaño incorrecto (J10) no hay partida con qué comparar: ni se compara ni se aparta. */
    @Test
    fun aWrongSizeSaveRejectsContinueWithAnHonestReasonAndKeepsTheState() {
        val first = openFresh().game
        val fingerprint = first.fingerprint
        playAndExit(first)
        val auto = states(fingerprint).load(StateSlot.AUTO)
        val wrong = ByteArray(100) { 7 }
        saves(fingerprint).saveFile.writeBytes(wrong)

        val result = open(LaunchMode.RESUME)
        assertEquals(OpenResult.Failed(OpenError.ResumeFailed(ResumeFailure.SAVE_NOT_LOADED, fingerprint)), result)
        assertArrayEquals("el AUTO sigue en su ranura", auto, states(fingerprint).load(StateSlot.AUTO))
        assertTrue("no se aparta nada", states(fingerprint).obsoleteAutoFiles().isEmpty())
        assertArrayEquals("el .sav ajeno no se toca", wrong, saves(fingerprint).saveFile.readBytes())
        assertTrue(noSaveThreadAlive())
    }

    /** A9-H2: una sesión que no guarda (J10) aparta, una sola vez, el AUTO que había antes en vez de pisarlo. */
    @Test
    fun aSessionThatCannotSaveNeverOverwritesThePreviousAutomaticState() {
        val first = openFresh().game
        val fingerprint = first.fingerprint
        playAndExit(first)
        val previous = states(fingerprint).load(StateSlot.AUTO)
        val wrong = ByteArray(100) { 7 }
        saves(fingerprint).saveFile.writeBytes(wrong)

        val unsaved = openFresh().game
        assertFalse("J10: esta sesión no guarda", unsaved.persists)
        unsaved.start()
        Thread.sleep(200)
        unsaved.pause()
        assertTrue(unsaved.saveAutoStateIfParked()) // como ON_STOP
        assertEquals(ExitResult.Clean, unsaved.exit()) // y al salir, otra vez
        val kept = states(fingerprint).obsoleteAutoFiles()
        assertEquals("solo el AUTO anterior a la sesión se aparta", 1, kept.size)
        assertArrayEquals("con su contenido intacto", previous, kept.single().readBytes())
        assertTrue(states(fingerprint).stateFile(StateSlot.AUTO).exists())
        assertArrayEquals(wrong, saves(fingerprint).saveFile.readBytes())
    }

    /** A9-H3: mientras el AUTO es el «deshacer» de «Guardar actual y cargar», `ON_STOP` no lo pisa; la salida sí. */
    @Test
    fun theUndoOfALoadIsNotOverwrittenInTheBackgroundButExitStillWritesTheAutomaticState() {
        val game = openFresh().game
        val fingerprint = game.fingerprint
        game.start()
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        game.pause()
        game.saveState(StateSlot.MANUAL1)
        game.resume()
        assertTrue(waitUntil { game.session.sramDirtySequence() > 10 })
        game.pause()
        assertTrue("sin carga, el AUTO de segundo plano se escribe", game.saveAutoStateIfParked())
        assertFalse(game.autoHoldsLoadUndo)

        game.loadState(StateSlot.MANUAL1) // «Guardar actual y cargar»: el AUTO pasa a ser el deshacer
        assertTrue(game.autoHoldsLoadUndo)
        val undo = states(fingerprint).load(StateSlot.AUTO)
        assertFalse(game.saveAutoStateIfParked())
        assertArrayEquals("ON_STOP no pisa el deshacer", undo, states(fingerprint).load(StateSlot.AUTO))

        assertEquals(ExitResult.Clean, game.exit())
        assertFalse("al salir se guarda el AUTO como antes de A9", undo.contentEquals(states(fingerprint).load(StateSlot.AUTO)))
    }

    /** Sesión cuyo segundo `loadStateRaw` (el rollback) falla: el núcleo deja de ser de fiar. */
    private class RollbackFailsSession : EmulatorSession() {
        @Volatile var loads = 0
        override fun loadStateRaw(data: ByteArray) {
            if (++loads == 2) throw CoreError.StateCorrupt()
            super.loadStateRaw(data)
        }
    }

    /** A9-H4: tras un rollback fallido (persistencia desactivada) ni salir ni el rescate escriben un AUTO del núcleo dudoso. */
    @Test
    fun anUntrustedCoreNeverWritesTheAutomaticStateOnExitOrRescue() {
        for (rescue in listOf(false, true)) {
            val ops = FailableOps()
            openGame(SyntheticRom.sramCounter(), ops = ops, session = RollbackFailsSession(), autoTick = false).use { g ->
                val game = g.game
                game.start()
                assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
                game.pause()
                game.saveState(StateSlot.MANUAL1)
                game.resume()
                assertTrue(waitUntil { game.session.sramDirtySequence() > 10 })
                game.pause()
                ops.failSav = true
                assertThrows(StateError.RollbackFailed::class.java) { game.loadState(StateSlot.MANUAL1) }
                ops.failSav = false // la reparación de la partida anterior termina; el núcleo sigue sin ser de fiar
                assertFalse(game.persists)
                val undo = g.states.load(StateSlot.AUTO) // el deshacer se escribió antes, con el núcleo aún de fiar
                assertFalse(game.saveAutoStateIfParked())
                if (rescue) {
                    assertEquals(RescueOutcome.Closed, game.rescueExit(attempts = 1, retryDelayMs = 0))
                } else {
                    assertEquals(ExitResult.Clean, game.exit())
                }
                assertArrayEquals("rescate=$rescue: el AUTO no se reescribe", undo, g.states.load(StateSlot.AUTO))
            }
        }
    }

    /** A9-H6: `onStopped` del ViewModel escribe el AUTO con la partida aparcada y recalcula «Continuar». */
    @Test
    fun theViewModelWritesTheBackgroundAutomaticStateAndOffersContinue() {
        val vm = GameplayViewModel(launcher = launcher)
        try {
            vm.refreshContinuations(emptySet())
            vm.open(entry)
            assertTrue(waitUntil(10_000) { vm.game.value != null })
            val game = vm.game.value!!
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
            assertFalse(game.fingerprint in vm.resumable.value)
            vm.onBackground() // ON_PAUSE/ON_STOP: pausa y vaciado en el hilo principal
            vm.onStopped()
            assertTrue("AUTO escrito", waitUntil(10_000) { states(game.fingerprint).stateFile(StateSlot.AUTO).exists() })
            assertTrue("«Continuar» disponible", waitUntil(10_000) { game.fingerprint in vm.resumable.value })
            val auto = states(game.fingerprint).stateFile(StateSlot.AUTO)
            assertTrue(auto.lastModified() >= saves(game.fingerprint).saveFile.lastModified())
        } finally {
            vm.game.value?.close()
        }
    }
}
