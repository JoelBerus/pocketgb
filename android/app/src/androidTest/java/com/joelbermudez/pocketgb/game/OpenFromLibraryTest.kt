package com.joelbermudez.pocketgb.game

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.library.ContentResolverRomSource
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomProblem
import com.joelbermudez.pocketgb.library.SafDocumentTree
import com.joelbermudez.pocketgb.saf.TestDocumentsProvider
import com.joelbermudez.pocketgb.saf.TestFixtures
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.SaveMirror
import com.joelbermudez.pocketgb.saves.SaveOpening
import com.joelbermudez.pocketgb.saves.SavesIndex
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.saf.SafSaveMirror
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Apertura de extremo a extremo: biblioteca SAF (proveedor de pruebas) → lanzador → sesión → guardado local y espejo. */
@RunWith(AndroidJUnit4::class)
class OpenFromLibraryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
    private val fixtures = TestFixtures(resolver)
    private lateinit var root: File
    private lateinit var launcher: GameLauncher
    private val opened = mutableListOf<GameSession>()

    private fun save(value: Int, size: Int = 8192) = ByteArray(size) { value.toByte() }

    private fun launcher(locator: MirrorLocator? = null) = GameLauncher(
        roms = ContentResolverRomSource(resolver),
        savesDirectory = File(root, "saves"),
        statesRoot = File(root, "states"),
        mirrors = locator ?: MirrorLocator { entry, store, sizes, onDisabled ->
            val mirror = SafSaveMirror(resolver, treeUri, entry.folderDocumentId!!, entry.fileName, store, sizes, true, onDisabled)
            MirrorSetup(mirror, mirror.mirrorMode())
        },
        registry = MirrorChannelRegistry(),
    )

    @Before
    fun setUp() {
        fixtures.reset()
        fixtures.put("Contador.gb", SyntheticRom.sramCounter())
        root = tempDir("open-library")
        launcher = launcher()
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        fixtures.reset()
        root.deleteRecursively()
    }

    private fun entry(id: String = "Contador.gb"): RomEntry =
        LibraryScanner.scan(SafDocumentTree(resolver, treeUri)).first { it.id == id }

    private fun open(entry: RomEntry = entry()): OpenResult.Opened =
        (launcher.openBlocking(entry) as OpenResult.Opened).also { opened += it.game }

    private fun playAndSave(game: GameSession) {
        game.start()
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        assertEquals(com.joelbermudez.pocketgb.saves.FlushResult.Saved, game.pause())
    }

    @Test
    fun theSramWrittenByTheGameLandsInFilesDirSavesAndInTheMirror() {
        val result = open()
        val game = result.game
        assertNull(result.warning)
        playAndSave(game)
        val local = File(root, "saves/${game.fingerprint}.sav")
        assertTrue(local.exists())
        assertArrayEquals(game.session.copySram(), local.readBytes())
        assertTrue("el espejo junto a la ROM se actualiza", waitUntil(15_000) { fixtures.read("Contador.sav")?.contentEquals(local.readBytes()) == true })
        val record = SavesIndex(File(root, "saves")).load().getValue(game.fingerprint)
        assertEquals("CONTADOR", record.title)
        assertEquals("Contador.gb", record.fileName)
        // El historial del espejo se escribe (tmp + rename) en el hilo del espejo: se espera a que termine.
        assertTrue("sin temporales", waitUntil(10_000) { File(root, "saves").walkTopDown().none { it.name.endsWith(".tmp") } })
    }

    @Test
    fun anExistingSavNextToTheRomIsImportedWhenThereIsNoLocalSave() {
        fixtures.put("Contador.sav", save(0).also { it[0] = 0x40 })
        val result = open()
        assertEquals(0x40, result.game.session.copySram()[0].toInt())
        assertTrue(File(root, "saves/${result.game.fingerprint}.sav").exists()) // instalado en local
    }

    @Test
    fun aReadOnlyFolderOpensWithAWarningAndNeverWritesTheMirror() {
        fixtures.readOnlyFlags(true)
        val result = open()
        assertEquals(SaveLoadWarning.MirrorReadOnly, result.warning)
        playAndSave(result.game)
        Thread.sleep(300)
        assertNull("nunca se crea el espejo", fixtures.read("Contador.sav"))
        assertTrue(File(root, "saves/${result.game.fingerprint}.sav").exists())
    }

    @Test
    fun aSavWithTheWrongSizeOpensWithoutPersistingAnything() {
        fixtures.put("Contador.sav", ByteArray(100) { 7 })
        val result = open()
        assertEquals(SaveLoadWarning.MirrorWrongSizeOnly, result.warning)
        assertTrue(!result.game.persists)
        result.game.start()
        Thread.sleep(300)
        result.game.pause()
        assertArrayEquals("el .sav ajeno no se toca", ByteArray(100) { 7 }, fixtures.read("Contador.sav"))
        assertTrue(!File(root, "saves/${result.game.fingerprint}.sav").exists())
    }

    @Test
    fun siblingRomsSharingTheSavNameDisableTheMirror() {
        fixtures.put("CONTADOR.GBC", SyntheticRom.sramCounter())
        val result = open(entry("Contador.gb"))
        assertEquals(SaveLoadWarning.MirrorShared, result.warning)
        playAndSave(result.game)
        Thread.sleep(300)
        assertNull(fixtures.read("Contador.sav"))
    }

    @Test
    fun missingRomAndUnplayableEntriesFailWithActionableErrors() {
        val real = entry()
        val gone = real.copy(id = "Fantasma.gb", uri = real.uri.replace("Contador.gb", "Fantasma.gb"))
        val missing = launcher.openBlocking(gone) as OpenResult.Failed
        // El proveedor de pruebas cuenta como remoto (como en A4): un documento ausente es "pendiente".
        assertTrue("falta el archivo: ${missing.error}", missing.error in setOf(OpenError.FolderMissing, OpenError.Unreadable, OpenError.RemotePending))
        val broken = launcher.openBlocking(real.copy(problem = RomProblem.INVALID_HEADER)) as OpenResult.Failed
        assertEquals(OpenError.Unplayable(RomProblem.INVALID_HEADER), broken.error)
        fixtures.put("Basura.gb", ByteArray(40_000) { 1 })
        val rejected = launcher.openBlocking(entry("Basura.gb")) as OpenResult.Failed
        assertTrue(rejected.error is OpenError.RomRejected)
        // Ningún intento fallido deja hilos de guardado ni sesiones vivas.
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "pocketgb-saves" && it.isAlive })
    }

    @Test
    fun anUnreadableMirrorWithNoLocalSaveRefusesToOpen() {
        val locator = MirrorLocator { _, _, _, _ ->
            val unavailable = object : SaveMirror {
                override fun snapshot() = SaveMirror.Snapshot.Unavailable
                override fun write(data: ByteArray): Long? = null
            }
            MirrorSetup(unavailable, SaveOpening.MirrorMode.ReadWrite)
        }
        val result = launcher(locator).openBlocking(entry()) as OpenResult.Failed
        assertEquals(OpenError.MirrorNotDownloaded, result.error)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "pocketgb-saves" && it.isAlive })
    }

    @Test
    fun theViewModelOpensRecordsPlayedGuardsDoubleOpenAndExitsCleanly() {
        val recorded = AtomicReference<Triple<String, String, Long>?>()
        val vm = GameplayViewModel(
            launcher = launcher,
            recordPlayed = { e, fp, at -> recorded.set(Triple(e.id, fp, at)) },
            now = { 1234L },
        )
        try {
            val entry = entry()
            vm.open(entry)
            vm.open(entry) // doble toque: ignorado
            assertTrue(waitUntil(10_000) { vm.game.value != null })
            val game = vm.game.value!!
            assertEquals(Triple("Contador.gb", game.fingerprint, 1234L), recorded.get())
            assertEquals(game.fingerprint, vm.openFingerprint.value.let { it ?: waitUntil { vm.openFingerprint.value != null }.let { vm.openFingerprint.value } })
            assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })

            vm.exit()
            assertTrue(waitUntil(10_000) { vm.game.value == null })
            assertTrue(game.isClosed)
            assertTrue(File(root, "states/${game.fingerprint}/${StateSlot.AUTO.fileStem}.state").exists())
            assertNotNull(File(root, "saves/${game.fingerprint}.sav").takeIf { it.exists() })
        } finally {
            vm.game.value?.close()
        }
    }
}
