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
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
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
        assertArrayEquals("…apartado, no borrado", auto, states(fingerprint).obsoleteAutoFile.readBytes())

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
            assertEquals(ResumeFailure.NOT_CURRENT, (vm.dialog.value as GameDialog.ResumeFailed).reason)
            assertTrue("deja de ofrecerse «Continuar»", waitUntil(5_000) { fingerprint !in vm.resumable.value })
            vm.playFromStartAfterResumeFailure()
            assertTrue(waitUntil(10_000) { vm.game.value != null })
            assertArrayEquals(newer, vm.game.value!!.session.copySram())
            vm.exit()
            assertTrue(waitUntil(10_000) { vm.game.value == null })
            assertTrue("al salir hay AUTO nuevo y vuelve «Continuar»", waitUntil(5_000) { fingerprint in vm.resumable.value })
        } finally {
            vm.game.value?.close()
        }
    }
}
