package com.joelbermudez.pocketgb.game

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.GbaBios
import com.joelbermudez.pocketgb.emulator.GbaOptions
import com.joelbermudez.pocketgb.emulator.GbaRtc
import com.joelbermudez.pocketgb.emulator.GbaSaveType
import com.joelbermudez.pocketgb.library.ContentResolverRomSource
import com.joelbermudez.pocketgb.library.GbaBiosSource
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.RomConsole
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.SafDocumentTree
import com.joelbermudez.pocketgb.saf.TestDocumentsProvider
import com.joelbermudez.pocketgb.saf.TestFixtures
import com.joelbermudez.pocketgb.saves.FlushResult
import com.joelbermudez.pocketgb.saves.LaunchMode
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.SavesIndex
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.saves.saf.SafSaveMirror
import com.joelbermudez.pocketgb.testing.GbaTestRoms
import com.joelbermudez.pocketgb.testing.SyntheticGbaRom
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N8 Kotlin de extremo a extremo con el núcleo GBA real: biblioteca SAF (proveedor de pruebas) → lanzador → sesión de
 * GBA → partida local y espejo `<rom>.sav` (medio + 16 B de RTC, formato de iOS), estados, continuación exacta y BIOS.
 * ROMs libres: `arm.gba` (jsmolka, MIT), `eeprom8k.gba` (homebrew propia, MIT) y sintéticas.
 */
@RunWith(AndroidJUnit4::class)
class GbaGameTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
    private val fixtures = TestFixtures(resolver)
    private lateinit var root: File
    private val opened = mutableListOf<GameSession>()
    private var gbaOptions = GbaOptions()
    private var bios: ByteArray? = null
    private val biosReads = AtomicInteger()

    private fun launcher() = GameLauncher(
        roms = ContentResolverRomSource(resolver),
        savesDirectory = File(root, "saves"),
        statesRoot = File(root, "states"),
        mirrors = MirrorLocator { entry, store, sizes, onDisabled ->
            val mirror = SafSaveMirror(resolver, treeUri, entry.folderDocumentId!!, entry.fileName, store, sizes, true, onDisabled)
            MirrorSetup(mirror, mirror.mirrorMode())
        },
        registry = MirrorChannelRegistry(),
        gbaOptionsFor = { gbaOptions },
        biosReader = {
            biosReads.incrementAndGet()
            bios
        },
    )

    @Before
    fun setUp() {
        fixtures.reset()
        root = tempDir("n8-gba")
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        fixtures.reset()
        root.deleteRecursively()
    }

    private fun entry(path: String): RomEntry = LibraryScanner.scan(SafDocumentTree(resolver, treeUri)).first { it.id == path }

    private fun open(entry: RomEntry, mode: LaunchMode = LaunchMode.FRESH): OpenResult =
        launcher().openBlocking(entry, mode = mode).also { if (it is OpenResult.Opened) opened += it.game }

    private fun openGame(entry: RomEntry, mode: LaunchMode = LaunchMode.FRESH): OpenResult.Opened {
        val result = open(entry, mode)
        assertTrue("se abre: $result", result is OpenResult.Opened)
        return result as OpenResult.Opened
    }

    private fun sha256(pixels: IntArray): String {
        val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asIntBuffer().put(pixels) }.array()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Pie de RTC de 16 B (iOS/`gba_rtc_save`): desplazamiento en segundos (LE, 8 B) y estado (24 h = 0x40). */
    private fun rtcFooter(offsetSeconds: Long): ByteArray = ByteArray(16).also { footer ->
        for (i in 0 until 8) footer[i] = (offsetSeconds ushr (8 * i)).toByte()
        footer[8] = 0x40
    }

    @Test
    fun armGbaFromTheSafLibraryReachesTheAllTestsPassedScreen() {
        fixtures.put("GBA/arm.gba", GbaTestRoms.load("arm.gba"))
        val entry = entry("GBA/arm.gba")
        assertEquals(RomConsole.GBA, entry.console)
        assertEquals(listOf("GBA"), entry.folderPath)
        assertNull(entry.problem)
        val game = openGame(entry).game
        assertEquals(Console.GBA, game.session.console)
        assertEquals(240 * 160, game.session.screen.pixelCount)
        assertFalse("sin BIOS del usuario: HLE", game.info.biosLoaded)
        game.start()
        assertTrue(waitUntil { game.session.frameCount > 60 })
        game.pause()
        assertEquals("pantalla «All tests passed» de jsmolka", JSMOLKA_PASS, sha256(game.session.copyFrame()))
        val record = SavesIndex(File(root, "saves")).load().getValue(game.fingerprint)
        assertEquals("arm.gba", record.fileName)
    }

    @Test
    fun aGbaSaveWithItsClockGoesToFilesDirAndTheMirrorAndComesBackIdentical() {
        gbaOptions = GbaOptions(rtc = GbaRtc.ON)
        val footer = rtcFooter(3L * 86_400 + 1234)
        fixtures.put("GBA/Contador.gba", SyntheticGbaRom.sramCounter())
        fixtures.put("GBA/Contador.sav", ByteArray(32768) + footer)
        val entry = entry("GBA/Contador.gba")
        val first = openGame(entry)
        val game = first.game
        assertNull(first.warning)
        assertEquals("el espejo se importa con su reloj", 32768 + 16, game.session.copySram().size)
        assertArrayEquals(footer, game.session.copySram().copyOfRange(32768, 32784))
        game.start()
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        assertEquals(FlushResult.Saved, game.pause())
        val local = SaveStore(File(root, "saves"), game.fingerprint).saveFile
        assertEquals(32784, local.length().toInt())
        assertArrayEquals("el reloj del juego sigue en el pie", footer, local.readBytes().copyOfRange(32768, 32784))
        assertTrue(
            "el espejo junto al ROM es la copia local",
            waitUntil(15_000) { fixtures.read("GBA/Contador.sav")?.contentEquals(local.readBytes()) == true },
        )
        val saved = local.readBytes()
        assertEquals(ExitResult.Clean, game.exit())
        game.close()

        // Ida y vuelta: al volver a abrir, la partida y el reloj son los mismos bytes.
        val again = openGame(entry).game
        assertArrayEquals(saved, again.session.copySram())
        assertTrue(waitUntil(10_000) { File(root, "saves").walkTopDown().none { it.name.endsWith(".tmp") } })
    }

    @Test
    fun gbaStatesSaveAndLoadWithA240x160Capture() {
        fixtures.put("Contador.gba", SyntheticGbaRom.sramCounter())
        val game = openGame(entry("Contador.gba")).game
        game.start()
        assertTrue(waitUntil { game.session.frameCount > 10 })
        game.pause()
        game.saveState(StateSlot.MANUAL1)
        val stored = game.states().getValue(StateSlot.MANUAL1)
        assertFalse("la firma PGBA es válida", stored.corrupt)
        val png = stored.thumbnail!!
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(240, bitmap.width)
        assertEquals(160, bitmap.height)
        game.resume()
        assertTrue(waitUntil { game.session.frameCount > 40 })
        game.pause()
        game.loadState(StateSlot.MANUAL1)
        assertEquals(com.joelbermudez.pocketgb.emulator.SessionState.Paused, game.session.state.value)
    }

    @Test
    fun continueResumesTheExactGbaStateAndEeprom8KiBLoadsTheSaveFirst() {
        // eeprom8k.gba confirma 8 KiB por DMA y escribe el bloque 1000: el `.sav` es de 8 KiB y el AUTO lo exige.
        fixtures.put("EEPROM/eeprom8k.gba", GbaTestRoms.load("eeprom8k.gba"))
        val entry = entry("EEPROM/eeprom8k.gba")
        val first = openGame(entry).game
        first.start()
        assertTrue("la DMA confirma 8 KiB", waitUntil { first.session.sramSaveSize == 8192 })
        assertTrue(waitUntil { first.session.frameCount > 120 })
        assertEquals(ExitResult.Clean, first.exit())
        val fingerprint = first.fingerprint
        first.close()
        assertEquals(8192, SaveStore(File(root, "saves"), fingerprint).saveFile.length().toInt())
        val auto = StateStore(File(root, "states"), fingerprint).load(StateSlot.AUTO)
        assertEquals("PGBA", String(auto.copyOf(4), Charsets.US_ASCII))

        val resumed = openGame(entry, LaunchMode.RESUME)
        assertTrue("«Continuar» retoma el AUTO de GBA", resumed.resumed)
        // El núcleo queda en el estado guardado; solo cambia la base del reloj, que se lleva a la hora actual al
        // retomar (iOS D81-H5) y con ella el CRC del estado: unos pocos bytes.
        val now = resumed.game.session.saveStateParked()
        assertEquals(auto.size, now.size)
        val differing = auto.indices.count { auto[it] != now[it] }
        assertTrue("difieren $differing bytes (reloj y CRC)", differing <= 32)
        assertEquals(8192, resumed.game.session.sramSaveSize)
        resumed.game.close()
        val fresh = openGame(entry).game.session.saveStateParked()
        assertFalse("desde el inicio es otro estado", auto.contentEquals(fresh))
    }

    @Test
    fun anEepromConfirmedAs8KiBOnlyByReadingStillGetsAn8KiBSaveSoContinueWorks() {
        fixtures.put("Lee8K.gba", SyntheticGbaRom.eepromReadOnly8k())
        val entry = entry("Lee8K.gba")
        val game = openGame(entry).game
        assertEquals("sin .sav la EEPROM empieza en 512 B", 512, game.session.sramSaveSize)
        game.start()
        assertTrue("la DMA de lectura confirma 8 KiB", waitUntil { game.session.sramSaveSize == 8192 })
        assertEquals("el juego no escribió nada", 0L, game.session.sramDirtySequence())
        // Sin pausar: el guardado periódico ve el cambio de tamaño como un guardado más (un cierre forzado ya deja el
        // `.sav` de 8 KiB). Pausar o salir también lo escriben (comparan el contenido).
        val local = SaveStore(File(root, "saves"), game.fingerprint).saveFile
        assertTrue("el .sav de 8 KiB se escribe mientras se juega", waitUntil(15_000) { local.length() == 8192L })
        game.pause()
        assertEquals(8192, local.length().toInt())
        assertEquals(ExitResult.Clean, game.exit())
        game.close()
        // Con el `.sav` de 8 KiB, el estado automático (ya con 8 KiB) se puede retomar: no da StateConfig.
        val resumed = openGame(entry, LaunchMode.RESUME)
        assertTrue(resumed.resumed)
        assertEquals(8192, resumed.game.session.sramSaveSize)
    }

    @Test
    fun aValidLocalSaveKeepsSavingWhileAMirrorOfAnotherSizeIsNeverOverwritten() {
        // H2: local válida para los ajustes forzados (SRAM 32 KiB) y espejo de otro tamaño (64 KiB).
        fixtures.put("Contador.gba", SyntheticGbaRom.sramCounter())
        val entry = entry("Contador.gba")
        val fingerprint = openGame(entry).game.also { it.close() }.fingerprint
        SaveStore(File(root, "saves"), fingerprint).save(ByteArray(32768))
        val mirror = ByteArray(65536) { 0x44 }
        fixtures.put("Contador.sav", mirror)
        gbaOptions = GbaOptions(saveType = GbaSaveType.SRAM)
        val result = openGame(entry)
        assertEquals(SaveLoadWarning.GameSettingsMismatch(noSave = false), result.warning)
        val game = result.game
        assertTrue("la local válida se sigue guardando", game.persists)
        game.start()
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        assertEquals(FlushResult.Saved, game.pause())
        val local = SaveStore(File(root, "saves"), fingerprint).load()!!
        assertEquals(32768, local.size)
        assertTrue("la partida avanzó", local.any { it != 0.toByte() })
        Thread.sleep(1_000)
        assertArrayEquals("el espejo de otro tamaño no se pisa", mirror, fixtures.read("Contador.sav"))
    }

    @Test
    fun forcedSettingsThatDoNotMatchTheSaveWarnAndNeverTouchIt() {
        fixtures.put("Contador.gba", SyntheticGbaRom.sramCounter())
        val entry = entry("Contador.gba")
        val first = openGame(entry).game
        val fingerprint = first.fingerprint
        first.close()
        val local = SaveStore(File(root, "saves"), fingerprint)
        local.save(ByteArray(32768) { 0x33 })
        gbaOptions = GbaOptions(saveType = GbaSaveType.FLASH64)
        val result = openGame(entry)
        assertEquals(SaveLoadWarning.GameSettingsMismatch(noSave = false), result.warning)
        assertFalse("esta sesión no guarda sobre la partida de 32 KiB", result.game.persists)
        result.game.start()
        Thread.sleep(300)
        result.game.pause()
        assertArrayEquals(ByteArray(32768) { 0x33 }, local.load())
    }

    @Test
    fun theBiosIsOnlyUsedWhenItIsTheOfficialOneAndNeverReadWithEmulated() {
        fixtures.put("Contador.gba", SyntheticGbaRom.sramCounter())
        val entry = entry("Contador.gba")
        bios = ByteArray(GbaBios.SIZE_BYTES) { 0x5A }
        val withJunk = openGame(entry).game
        assertEquals(1, biosReads.get())
        assertFalse("una BIOS que no es la oficial nunca llega al núcleo", withJunk.info.biosLoaded)
        withJunk.close()
        gbaOptions = GbaOptions(useBios = false)
        val emulated = openGame(entry).game
        assertEquals("con «Emulada» ni se lee", 1, biosReads.get())
        assertFalse(emulated.info.biosLoaded)
    }

    @Test
    fun theBiosFileIsReadFromTheRootOfTheSafFolder() {
        fixtures.put("gba_bios.bin", ByteArray(GbaBios.SIZE_BYTES) { 1 })
        fixtures.put("Sub/gba_bios.bin", ByteArray(GbaBios.SIZE_BYTES) { 2 })
        val read = GbaBiosSource.read(SafDocumentTree(resolver, treeUri))!!
        assertEquals(GbaBios.SIZE_BYTES, read.size)
        assertEquals(1.toByte(), read[0])
        assertEquals(com.joelbermudez.pocketgb.emulator.GbaBiosStatus.INVALID, GbaBios.status(read))
    }

    private companion object {
        const val JSMOLKA_PASS = "afc525600e609311057e2e71eb1e27c45b997a86b22ff4b11d8d9034a4192f33"
    }
}
