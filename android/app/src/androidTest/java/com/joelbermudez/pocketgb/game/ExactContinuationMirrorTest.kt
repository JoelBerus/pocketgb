package com.joelbermudez.pocketgb.game

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.library.ContentResolverRomSource
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.SafDocumentTree
import com.joelbermudez.pocketgb.saf.TestDocumentsProvider
import com.joelbermudez.pocketgb.saf.TestFixtures
import com.joelbermudez.pocketgb.saves.LaunchMode
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
import com.joelbermudez.pocketgb.saves.ResumeFailure
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.saves.saf.SafSaveMirror
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A9 · el caso que fue bloqueante en iOS (D81-H1): hay estado AUTO de una salida anterior y después llega junto al ROM
 * un `.sav` más nuevo (otro equipo, otro emulador, el cartucho volcado). «Continuar» NO puede cargar el estado: la
 * apertura instala el espejo en local (con backup de la anterior) y la continuación lo rechaza sin escribir nada. Ni
 * la copia local ni el espejo quedan con la partida vieja del estado.
 */
@RunWith(AndroidJUnit4::class)
class ExactContinuationMirrorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
    private val fixtures = TestFixtures(resolver)
    private lateinit var root: File
    private val opened = mutableListOf<GameSession>()

    private val launcher get() = GameLauncher(
        roms = ContentResolverRomSource(resolver),
        savesDirectory = File(root, "saves"),
        statesRoot = File(root, "states"),
        mirrors = MirrorLocator { entry, store, sizes, onDisabled ->
            val mirror = SafSaveMirror(resolver, treeUri, entry.folderDocumentId!!, entry.fileName, store, sizes, true, onDisabled)
            MirrorSetup(mirror, mirror.mirrorMode())
        },
        registry = MirrorChannelRegistry(),
    )

    @Before
    fun setUp() {
        fixtures.reset()
        fixtures.put("Contador.gb", SyntheticRom.sramCounter())
        root = tempDir("a9-mirror")
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        fixtures.reset()
        root.deleteRecursively()
    }

    private fun entry(): RomEntry = LibraryScanner.scan(SafDocumentTree(resolver, treeUri)).first { it.id == "Contador.gb" }

    @Test
    fun aNewerSaveNextToTheRomIsNeverOverwrittenByContinue() {
        val first = (launcher.openBlocking(entry()) as OpenResult.Opened).game.also { opened += it }
        val fingerprint = first.fingerprint
        first.start()
        assertTrue(waitUntil { first.session.sramDirtySequence() > 3 })
        assertEquals(ExitResult.Clean, first.exit())
        val store = SaveStore(File(root, "saves"), fingerprint)
        val oldLocal = store.load()!!
        assertTrue("el espejo se pone al día", waitUntil(15_000) { fixtures.read("Contador.sav")?.contentEquals(oldLocal) == true })
        val states = StateStore(File(root, "states"), fingerprint)
        assertTrue(states.stateFile(StateSlot.AUTO).exists())

        // Llega una partida más nueva junto al ROM (fecha posterior a todo lo local).
        val newer = ByteArray(8192) { 0x77 }
        fixtures.put("Contador.sav", newer, mtimeMs = System.currentTimeMillis() + 120_000)

        val result = launcher.openBlocking(entry(), mode = LaunchMode.RESUME)
        assertEquals(OpenResult.Failed(OpenError.ResumeFailed(ResumeFailure.NOT_CURRENT, fingerprint)), result)
        assertArrayEquals("la local pasa a ser la partida nueva", newer, store.load())
        assertTrue("la anterior queda como backup", store.backups().any { store.backupFile(it.index).readBytes().contentEquals(oldLocal) })
        Thread.sleep(500) // una escritura del espejo encolada por error ya habría empezado
        assertArrayEquals("el espejo no se pisa con la partida vieja del estado", newer, fixtures.read("Contador.sav"))
        assertFalse(states.stateFile(StateSlot.AUTO).exists())
        assertEquals("el estado se aparta, no se borra", 1, states.obsoleteAutoFiles().size)

        // «Jugar desde el inicio» abre la partida nueva.
        val fresh = (launcher.openBlocking(entry()) as OpenResult.Opened).also { opened += it.game }
        assertArrayEquals(newer, fresh.game.session.copySram())
    }
}
