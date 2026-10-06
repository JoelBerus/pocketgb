package com.joelbermudez.pocketgb.saf

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.library.ContentResolverRomSource
import com.joelbermudez.pocketgb.library.CoreRomInspector
import com.joelbermudez.pocketgb.library.DetailsError
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.DocumentReadException
import com.joelbermudez.pocketgb.library.FolderGrant
import com.joelbermudez.pocketgb.library.FolderStore
import com.joelbermudez.pocketgb.library.LibraryError
import com.joelbermudez.pocketgb.library.LibraryPreferencesFile
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomProblem
import com.joelbermudez.pocketgb.library.SafDocumentTree
import com.joelbermudez.pocketgb.library.TreeMissingException
import com.joelbermudez.pocketgb.library.TreeNode
import com.joelbermudez.pocketgb.library.TreePermissionException
import com.joelbermudez.pocketgb.testing.SyntheticRom
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafLibraryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val targetContext = instrumentation.targetContext
    private val resolver = targetContext.contentResolver
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(
        TestDocumentsProvider.AUTHORITY,
        TestDocumentsProvider.ROOT_ID,
    )
    private val fixtures = TestFixtures(resolver)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefsDir = File(targetContext.cacheDir, "saf-test-prefs")

    @Before
    fun setUp() {
        prefsDir.deleteRecursively()
        fixtures.reset()
        fixtures.mkdir("Sub/Deeper")
        fixtures.put("Alfa.gb", SyntheticRom.romOnly("ALFA"))
        fixtures.put("Beta.GBC", SyntheticRom.romOnly("BETA", color = true))
        fixtures.put("notas.txt", "no es un ROM".toByteArray())
        fixtures.put("Sub/Gamma.gb", SyntheticRom.romOnly("GAMMA"))
        fixtures.put("Sub/Deeper/Oculto.gb", SyntheticRom.romOnly("OCULTO"))
        fixtures.putSparse("Grande.gb", 9L * 1024 * 1024)
        fixtures.put("Roto.gb", ByteArray(16))
        fixtures.put("virtual-Remoto.gb", ByteArray(0))
    }

    @After
    fun tearDown() {
        scope.cancel()
        fixtures.reset()
        prefsDir.deleteRecursively()
    }

    private fun tree() = SafDocumentTree(resolver, treeUri)

    @Test
    fun scanListsRomsInRootAndOneSubfolderWithTypedProblems() {
        val entries = LibraryScanner.scan(tree()).associateBy { it.id }

        assertEquals(
            setOf("Alfa.gb", "Beta.GBC", "Sub/Gamma.gb", "Grande.gb", "Roto.gb", "virtual-Remoto.gb"),
            entries.keys,
        )
        assertFalse("Un nivel más profundo no se escanea", entries.keys.any { it.contains("Oculto") })
        assertEquals("ALFA", entries.getValue("Alfa.gb").title)
        assertNull(entries.getValue("Alfa.gb").problem)
        assertTrue(entries.getValue("Beta.GBC").isColor)
        assertEquals("Sub", entries.getValue("Sub/Gamma.gb").subfolder)
        assertEquals(RomProblem.TOO_LARGE, entries.getValue("Grande.gb").problem)
        assertEquals(RomProblem.INVALID_HEADER, entries.getValue("Roto.gb").problem)
        assertEquals(RomProblem.REMOTE_UNAVAILABLE, entries.getValue("virtual-Remoto.gb").problem)
        assertTrue(entries.getValue("Alfa.gb").uri.startsWith("content://${TestDocumentsProvider.AUTHORITY}/tree/"))
    }

    @Test
    fun readHeadReturnsAtMostTheLimitAndNeverMoreThanTheFile() {
        val tree = tree()
        val alfa = tree.children(null).single { it.name == "Alfa.gb" }
        assertEquals(0x150, tree.readHead(alfa, 0x150).size)
        assertEquals(32 * 1024, tree.readHead(alfa, 1024 * 1024).size)
        assertTrue(tree.readHead(alfa, 16).contentEquals(SyntheticRom.romOnly("ALFA").copyOf(16)))
    }

    @Test
    fun childrenMarksDirectoriesAndVirtualDocuments() {
        val nodes = tree().children(null).associateBy { it.name }
        assertTrue(nodes.getValue("Sub").isDirectory)
        assertFalse(nodes.getValue("Alfa.gb").isDirectory)
        assertEquals(32L * 1024, nodes.getValue("Alfa.gb").sizeBytes)
        assertTrue(nodes.getValue("virtual-Remoto.gb").isVirtual)
        assertFalse(nodes.getValue("Alfa.gb").isVirtual)
    }

    @Test
    fun unreadableDocumentIsAReadExceptionNotACrash() {
        val tree = tree()
        val ghost = TreeNode("root/Fantasma.gb", "Fantasma.gb", isDirectory = false, sizeBytes = 100)
        val error = assertThrows(DocumentReadException::class.java) { tree.readHead(ghost, 0x150) }
        // El proveedor de prueba no es un proveedor local del sistema, así que se trata como remoto.
        assertTrue(error.remote)
    }

    @Test
    fun missingSubfolderIsAnIoErrorAndMissingRootIsTreeMissing() {
        assertThrows(IOException::class.java) { tree().children("root/NoExiste") }
        fixtures.deleteAll()
        assertThrows(TreeMissingException::class.java) { tree().children(null) }
    }

    @Test
    fun revokedGrantIsReportedAsPermissionError() {
        fixtures.deny(true)
        assertThrows(TreePermissionException::class.java) { tree().children(null) }
    }

    @Test
    fun scanNeverModifiesTheProviderFiles() {
        val before = fixtures.snapshot()
        LibraryScanner.scan(tree())
        assertEquals(before, fixtures.snapshot())
    }

    private class MemoryFolderStore(var uri: String?) : FolderStore {
        override fun currentUri() = uri
        override fun hasPersistedPermission() = uri != null
        override fun select(uri: String): FolderGrant {
            this.uri = uri
            return FolderGrant(writeGranted = false)
        }
        override fun forget() {
            uri = null
        }
        override fun displayName() = "Juegos de prueba"
    }

    private fun viewModel() = LibraryViewModel(
        folders = MemoryFolderStore(treeUri.toString()),
        openTree = { SafDocumentTree(resolver, Uri.parse(it)) },
        roms = ContentResolverRomSource(resolver),
        inspector = CoreRomInspector(),
        preferencesFile = LibraryPreferencesFile(File(prefsDir, "library.json")),
        io = Dispatchers.IO,
        scope = scope,
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(15_000) { block() } }

    @Test
    fun viewModelScansAndLoadsDetailsThroughTheRealCore() {
        val vm = viewModel()
        vm.rescan()
        val ready = await { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready
        assertEquals("Juegos de prueba", ready.folderName)
        val alfa = ready.entries.single { it.id == "Alfa.gb" }

        val loaded = await { vm.loadDetails(alfa.id) } as DetailsLoad.Loaded
        assertEquals("Solo ROM", loaded.details.cartridge)
        assertEquals(32 * 1024, loaded.details.romBytes)
        assertTrue(loaded.details.headerChecksumOk)
        assertEquals(64, loaded.details.fingerprint.length)
        await { vm.flushPreferences() }
        assertEquals(loaded.details.fingerprint, LibraryPreferencesFile(File(prefsDir, "library.json")).load().fingerprints[alfa.id])

        val big = ready.entries.single { it.id == "Grande.gb" }
        assertEquals(
            DetailsLoad.Failed(DetailsError.Problem(RomProblem.TOO_LARGE)),
            await { vm.loadDetails(big.id) },
        )
        val broken = ready.entries.single { it.id == "Roto.gb" }
        assertTrue((await { vm.loadDetails(broken.id) } as DetailsLoad.Failed).error is DetailsError.Problem)
    }

    @Test
    fun viewModelReportsRevokedAndMissingFolders() {
        val vm = viewModel()
        fixtures.deny(true)
        vm.rescan()
        await { vm.state.first { it == LibraryState.Failed(LibraryError.PermissionRevoked) } }

        fixtures.deny(false)
        fixtures.deleteAll()
        vm.rescan()
        await { vm.state.first { it == LibraryState.Failed(LibraryError.FolderMissing) } }
        assertNotNull(vm.folderName.value)
    }

    // ---- Correcciones de la 1ª vuelta de auditoría ----

    @Test
    fun revocationOnASubfolderPropagatesAndTheViewModelReportsPermissionRevoked() {
        fixtures.denyDir("root/Sub") // la raíz se lista bien; el permiso desaparece al entrar en Sub
        assertTrue(tree().children(null).isNotEmpty()) // la raíz sí responde
        assertThrows(TreePermissionException::class.java) { LibraryScanner.scan(tree()) }

        val vm = viewModel()
        vm.rescan()
        await { vm.state.first { it == LibraryState.Failed(LibraryError.PermissionRevoked) } }
    }

    @Test
    fun providerThrowingIllegalStateIsAnIoErrorNotACrash() {
        fixtures.throwDir("root/Sub")
        assertThrows(IOException::class.java) { tree().children("root/Sub") }
        // Un fallo no-permiso en una subcarpeta se ignora: el resto de la biblioteca sigue disponible.
        val ids = LibraryScanner.scan(tree()).map { it.id }
        assertTrue("Alfa.gb" in ids)
        assertFalse(ids.any { it.startsWith("Sub/") })

        fixtures.throwDir("root")
        val vm = viewModel()
        vm.rescan()
        await { vm.state.first { it == LibraryState.Failed(LibraryError.Unreadable) } }
    }

    @Test
    fun cursorWithoutOptionalColumnsOrWithTextSizeIsTolerated() {
        fixtures.omitSizeColumn(true)
        val withoutSize = tree().children(null).associateBy { it.name }
        assertEquals(0L, withoutSize.getValue("Alfa.gb").sizeBytes)
        fixtures.omitSizeColumn(false)

        fixtures.omitMimeColumn(true)
        val withoutMime = tree().children(null)
        assertTrue(withoutMime.none { it.isDirectory })
        fixtures.omitMimeColumn(false)

        fixtures.textSize(true)
        val textSize = tree().children(null).associateBy { it.name }
        assertEquals("tamaño no numérico = desconocido", 0L, textSize.getValue("Alfa.gb").sizeBytes)
        val entries = LibraryScanner.scan(tree()).associateBy { it.id }
        assertNull(entries.getValue("Alfa.gb").problem)
    }

    @Test
    fun declaredSmallSizeButHugeContentIsStopppedByTheReadCapAtMaxPlusOne() {
        // El proveedor anuncia 32 KiB pero el contenido real ocupa 9 MiB: el tope de lectura es la defensa.
        fixtures.putSparse("Mentiroso.gb", 9L * 1024 * 1024)
        fixtures.declareSize("root/Mentiroso.gb", 32L * 1024)
        val entry = LibraryScanner.scan(tree()).single { it.id == "Mentiroso.gb" }
        assertNull("el escáner confía en el tamaño anunciado", entry.problem)

        val limit = LibraryScanner.MAX_ROM_BYTES.toInt() + 1
        assertEquals(limit, ContentResolverRomSource(resolver).read(entry.uri, limit).size)

        val vm = viewModel()
        vm.rescan()
        await { vm.state.first { it is LibraryState.Ready } }
        assertEquals(DetailsLoad.Failed(DetailsError.TooLarge), await { vm.loadDetails("Mentiroso.gb") })
    }

    @Test
    fun romSourceMarksProviderFailuresAsRemoteWithTheSameCriterionAsTheTree() {
        val vm = viewModel()
        vm.rescan()
        val ready = await { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready
        val alfa = ready.entries.single { it.id == "Alfa.gb" }
        fixtures.deleteAll() // el documento desaparece tras escanear
        val error = assertThrows(DocumentReadException::class.java) {
            ContentResolverRomSource(resolver).read(alfa.uri, 0x150)
        }
        assertTrue(error.remote)
        assertEquals(DetailsLoad.Failed(DetailsError.Remote), await { vm.loadDetails(alfa.id) })
    }

    // ---- A6-L3 (K20): fecha del .sav junto al ROM, sin abrir el archivo ----

    @Test
    fun scanReadsTheSiblingSavModificationDateInRootAndSubfolder() {
        val rootMtime = 1_700_000_000_000L
        val subMtime = 1_710_000_000_000L
        fixtures.put("Alfa.sav", ByteArray(8192), rootMtime)
        fixtures.put("Sub/Gamma.sav", ByteArray(8192), subMtime)
        val entries = LibraryScanner.scan(tree()).associateBy { it.id }
        assertEquals(rootMtime, entries.getValue("Alfa.gb").mirrorSaveDate)
        assertEquals(subMtime, entries.getValue("Sub/Gamma.gb").mirrorSaveDate)
        assertNull(entries.getValue("Beta.GBC").mirrorSaveDate)
        assertFalse("el .sav no es una entrada", entries.keys.any { it.endsWith(".sav") })
    }
}
