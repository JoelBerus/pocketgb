package com.joelbermudez.pocketgb.library

import com.joelbermudez.pocketgb.emulator.CoreError
import com.joelbermudez.pocketgb.emulator.RomInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class FakeFolders(var uri: String? = null, var permission: Boolean = true) : FolderStore {
        var forgotten = false
        var selectFails = false
        override fun currentUri() = uri
        override fun hasPersistedPermission() = uri != null && permission
        override fun select(uri: String): FolderGrant {
            if (selectFails) throw SecurityException("no")
            this.uri = uri
            return FolderGrant(writeGranted = true)
        }
        override fun forget() {
            forgotten = true
            uri = null
        }
        override fun displayName() = uri?.substringAfterLast('/')
    }

    private class FakeTree(
        private val files: List<TreeNode>,
        private val heads: Map<String, ByteArray>,
        private val failure: IOException? = null,
        private val gate: CountDownLatch? = null,
    ) : DocumentTree {
        override fun children(directoryId: String?): List<TreeNode> {
            gate?.await(5, TimeUnit.SECONDS)
            failure?.let { throw it }
            return if (directoryId == null) files else emptyList()
        }
        override fun readHead(node: TreeNode, limit: Int) = heads.getValue(node.id).let { it.copyOf(minOf(limit, it.size)) }
        override fun uriOf(node: TreeNode) = "content://fake/${node.id}"
    }

    private fun rom(title: String): ByteArray {
        val bytes = ByteArray(0x150)
        title.forEachIndexed { i, c -> bytes[0x134 + i] = c.code.toByte() }
        var x = 0
        for (i in 0x134..0x14C) x = (x - (bytes[i].toInt() and 0xFF) - 1) and 0xFF
        bytes[0x14D] = x.toByte()
        return bytes
    }

    private fun node(name: String) = TreeNode(name, name, isDirectory = false, sizeBytes = 32768)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefsFile: LibraryPreferencesFile
    private lateinit var folders: FakeFolders

    private var tree: DocumentTree = FakeTree(
        listOf(node("b.gb"), node("a.gb")),
        mapOf("a.gb" to rom("ALPHA"), "b.gb" to rom("BETA")),
    )
    private var romBytes: (String, Int) -> ByteArray = { _, _ -> ByteArray(0x8000) }
    private var inspector: (ByteArray) -> RomInfo = { info() }

    @Before
    fun setUp() {
        prefsFile = LibraryPreferencesFile(File(temp.root, "prefs/library.json"))
        folders = FakeFolders(uri = "content://tree/Juegos")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun info(fingerprint: Byte = 7) = RomInfo(
        title = "ALPHA", cgbFlag = 0, cartType = 0x13, romBytes = 32768, sramBytes = 8192,
        hasBattery = true, hasRtc = false, headerChecksumOk = true, globalChecksumOk = false,
        cgbMode = false, cgbCompat = false, fingerprint = ByteArray(32) { fingerprint },
    )

    private fun viewModel() = LibraryViewModel(
        folders = folders,
        openTree = { tree },
        roms = RomSource { uri, limit -> romBytes(uri, limit) },
        inspector = { inspector(it) },
        preferencesFile = prefsFile,
        io = Dispatchers.IO,
        scope = scope,
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    /** Espera el resultado del escaneo; `NoFolder` es el estado inicial, así que no cuenta como resultado. */
    private fun LibraryViewModel.awaitSettled(): LibraryState = await {
        state.first { it is LibraryState.Ready || it is LibraryState.Failed }
    }

    @Test
    fun withoutFolderStaysNoFolder() {
        folders.uri = null
        val vm = viewModel()
        vm.rescan()
        Thread.sleep(200) // sin carpeta no hay escaneo que esperar
        assertEquals(LibraryState.NoFolder, vm.state.value)
    }

    @Test
    fun rescanListsEntriesInNaturalOrderAndPublishesFolderName() {
        val vm = viewModel()
        vm.rescan()
        val ready = await { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready
        assertEquals(listOf("ALPHA", "BETA"), ready.entries.map { it.title })
        assertEquals("Juegos", ready.folderName)
        assertEquals("Juegos", vm.folderName.value)
    }

    @Test
    fun revokedPermissionIsReportedWithoutTouchingTheTree() {
        folders.permission = false
        val vm = viewModel()
        vm.rescan()
        assertEquals(LibraryState.Failed(LibraryError.PermissionRevoked), vm.awaitSettled())
    }

    @Test
    fun providerFailuresMapToTypedErrors() {
        val cases = listOf(
            TreePermissionException() to LibraryError.PermissionRevoked,
            TreeMissingException() to LibraryError.FolderMissing,
            IOException("boom") to LibraryError.Unreadable,
        )
        for ((failure, expected) in cases) {
            tree = FakeTree(emptyList(), emptyMap(), failure = failure)
            val vm = viewModel()
            vm.rescan()
            assertEquals(LibraryState.Failed(expected), vm.awaitSettled())
        }
    }

    @Test
    fun secondRescanCancelsTheFirstAndKeepsPreviousEntriesWhileScanning() {
        val vm = viewModel()
        vm.rescan()
        await { vm.state.first { it is LibraryState.Ready } }
        val gate = CountDownLatch(1)
        tree = FakeTree(listOf(node("c.gb")), mapOf("c.gb" to rom("GAMMA")), gate = gate)
        vm.rescan()
        val scanning = await { vm.state.first { it is LibraryState.Scanning } } as LibraryState.Scanning
        assertEquals(listOf("ALPHA", "BETA"), scanning.previous.map { it.title })
        gate.countDown()
        val ready = await { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready
        assertEquals(listOf("GAMMA"), ready.entries.map { it.title })
    }

    @Test
    fun chooseFolderScansAndRejectedGrantFails() {
        folders.uri = null
        val vm = viewModel()
        vm.chooseFolder("content://tree/Nueva")
        val ready = await { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready
        assertEquals("Nueva", ready.folderName)

        folders.selectFails = true
        vm.chooseFolder("content://tree/Otra")
        await { vm.state.first { it == LibraryState.Failed(LibraryError.PermissionRevoked) } }
    }

    @Test
    fun forgetFolderReleasesItAndReturnsToNoFolder() {
        val vm = viewModel()
        vm.rescan()
        await { vm.state.first { it is LibraryState.Ready } }
        vm.forgetFolder()
        await { vm.state.first { it == LibraryState.NoFolder } }
        assertTrue(folders.forgotten)
        assertNull(vm.folderName.value)
    }

    @Test
    fun preferencesPersistInOrderAndSurviveANewViewModel() {
        val vm = viewModel()
        vm.rescan()
        val ready = await { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready
        val alpha = ready.entries.first()
        vm.toggleFavorite(alpha)
        vm.setLayout(LibraryLayout.LIST)
        vm.setSort(LibrarySort.RECENT)
        vm.hide(ready.entries.last())
        await { vm.flushPreferences() }

        val saved = prefsFile.load()
        assertEquals(setOf(alpha.id), saved.favorites)
        assertEquals(LibraryLayout.LIST, saved.layout)
        assertEquals(LibrarySort.RECENT, saved.sort)
        assertEquals(setOf(ready.entries.last().id), saved.hiddenPaths)

        val again = viewModel()
        assertEquals(saved, await { again.flushPreferences(); again.prefs.value })
    }

    @Test
    fun changesMadeBeforePreferencesLoadAreAppliedOnTopOfThem() {
        prefsFile.save(LibraryPreferencesData(favorites = setOf("viejo.gb")))
        val vm = viewModel()
        val entry = RomEntry("nuevo.gb", "content://x", "nuevo.gb", "N", false, 1, true, null)
        vm.toggleFavorite(entry)
        await { vm.flushPreferences() }
        assertEquals(setOf("viejo.gb", "nuevo.gb"), vm.prefs.value.favorites)
        assertEquals(setOf("viejo.gb", "nuevo.gb"), prefsFile.load().favorites)
    }

    @Test
    fun queryAndFilterAreIndependentState() {
        val vm = viewModel()
        vm.setQuery("al")
        vm.setFilter(LibraryFilter.GBC)
        assertEquals("al", vm.query.value)
        assertEquals(LibraryFilter.GBC, vm.filter.value)
    }

    private fun readyVm(): Pair<LibraryViewModel, RomEntry> {
        val vm = viewModel()
        vm.rescan()
        val ready = await { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready
        return vm to ready.entries.first()
    }

    @Test
    fun detailsLoadMetadataAndRecordTheFingerprint() {
        val (vm, entry) = readyVm()
        val load = await { vm.loadDetails(entry.id) } as DetailsLoad.Loaded
        assertEquals("MBC3 + RAM + batería", load.details.cartridge)
        assertEquals("07".repeat(32), load.details.fingerprint)
        assertFalse(load.details.globalChecksumOk)
        await { vm.flushPreferences() }
        assertEquals("07".repeat(32), prefsFile.load().fingerprints[entry.id])
    }

    @Test
    fun detailsReadAtMostTheRomLimitPlusOneAndRejectLargerFiles() {
        val (vm, entry) = readyVm()
        var requested = 0
        romBytes = { _, limit -> requested = limit; ByteArray(limit) }
        val load = await { vm.loadDetails(entry.id) }
        assertEquals(DetailsLoad.Failed(DetailsError.TooLarge), load)
        assertEquals(8 * 1024 * 1024 + 1, requested)
    }

    @Test
    fun detailsFailuresAreTyped() {
        val (vm, entry) = readyVm()
        romBytes = { _, _ -> throw DocumentReadException(remote = true) }
        assertEquals(DetailsLoad.Failed(DetailsError.Remote), await { vm.loadDetails(entry.id) })
        romBytes = { _, _ -> throw IOException("x") }
        assertEquals(DetailsLoad.Failed(DetailsError.Unreadable), await { vm.loadDetails(entry.id) })
        romBytes = { _, _ -> ByteArray(0x8000) }
        inspector = { throw CoreError.UnsupportedMbc() }
        val rejected = await { vm.loadDetails(entry.id) } as DetailsLoad.Failed
        assertTrue(rejected.error is DetailsError.CoreRejected)
        assertEquals(DetailsLoad.Failed(DetailsError.NotFound), await { vm.loadDetails("nada.gb") })
    }

    @Test
    fun detailsOfAProblemEntryNeverReadTheRom() {
        tree = FakeTree(listOf(node("x.gb")), mapOf("x.gb" to ByteArray(4)))
        val vm = viewModel()
        vm.rescan()
        val entry = (await { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready).entries.single()
        romBytes = { _, _ -> error("no debe leerse") }
        assertEquals(
            DetailsLoad.Failed(DetailsError.Problem(RomProblem.INVALID_HEADER)),
            await { vm.loadDetails(entry.id) },
        )
    }
}
