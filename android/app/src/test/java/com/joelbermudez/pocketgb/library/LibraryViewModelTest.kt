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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        @Volatile var selectGate: CountDownLatch? = null
        override fun currentUri() = uri
        override fun hasPersistedPermission() = uri != null && permission
        override fun select(uri: String): FolderGrant {
            selectGate?.await(5, TimeUnit.SECONDS)
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
        /** Se libera en cuanto el escaneo entra en `children` (para saber que ya está en marcha). */
        private val entered: CountDownLatch? = null,
        private val runtimeFailure: RuntimeException? = null,
        private val dirFailures: Map<String, Exception> = emptyMap(),
    ) : DocumentTree {
        override fun children(directoryId: String?): List<TreeNode> {
            entered?.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            failure?.let { throw it }
            runtimeFailure?.let { throw it }
            if (directoryId != null) dirFailures[directoryId]?.let { throw it }
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
    private lateinit var store: FlakyStore
    private lateinit var folders: FakeFolders

    private var tree: DocumentTree = FakeTree(
        listOf(node("b.gb"), node("a.gb")),
        mapOf("a.gb" to rom("ALPHA"), "b.gb" to rom("BETA")),
    )
    private var romBytes: (String, Int) -> ByteArray = { _, _ -> ByteArray(0x8000) }
    private var inspector: (ByteArray) -> RomInfo = { info() }

    /** Almacén de preferencias con fallos y puntos de sincronización inyectables. */
    private class FlakyStore(val delegate: LibraryPreferencesFile) : PreferencesStore {
        @Volatile var loadGate: CountDownLatch? = null
        @Volatile var loadHook: (() -> Unit)? = null
        @Volatile var loadFailures = 0
        @Volatile var saveFailures = 0
        val saves = java.util.concurrent.atomic.AtomicInteger()

        override fun load(): LibraryPreferencesData {
            loadHook?.invoke()
            loadGate?.await(5, TimeUnit.SECONDS)
            if (loadFailures > 0) {
                loadFailures--
                throw IOException("disco no disponible")
            }
            return delegate.load()
        }

        override fun save(data: LibraryPreferencesData) {
            if (saveFailures > 0) {
                saveFailures--
                throw IOException("sin espacio")
            }
            saves.incrementAndGet()
            delegate.save(data)
        }
    }

    @Before
    fun setUp() {
        prefsFile = LibraryPreferencesFile(File(temp.root, "prefs/library.json"))
        store = FlakyStore(prefsFile)
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
        preferencesFile = store,
        io = Dispatchers.IO,
        scope = scope,
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    /** Espera el resultado del escaneo; `NoFolder` es el estado inicial, así que no cuenta como resultado. */
    private fun LibraryViewModel.awaitSettled(): LibraryState = await {
        state.first { it is LibraryState.Ready || it is LibraryState.Failed }
    }

    @Test
    fun initialStateIsLoadingNotNoFolder() {
        val vm = viewModel()
        assertEquals(LibraryState.Loading, vm.state.value)
        assertTrue(LibraryState.Loading != LibraryState.NoFolder)
    }

    @Test
    fun withoutFolderEndsInNoFolderAfterTheFirstScan() {
        folders.uri = null
        val vm = viewModel()
        vm.rescan()
        await { vm.state.first { it == LibraryState.NoFolder } }
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
            // Red de seguridad (H3): proveedor mal portado, nunca debe escapar como excepción.
            IllegalStateException("proveedor roto") to LibraryError.Unreadable,
            UnsupportedOperationException("sin soporte") to LibraryError.Unreadable,
        )
        for ((failure, expected) in cases) {
            tree = if (failure is RuntimeException && failure !is SecurityException) {
                FakeTree(emptyList(), emptyMap(), runtimeFailure = failure)
            } else {
                FakeTree(emptyList(), emptyMap(), failure = failure as? IOException)
            }
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
    fun theFirstScanIsReallyCancelledAndNeverPublishesItsResult() {
        val recorded = java.util.concurrent.CopyOnWriteArrayList<LibraryState>()
        val vm = viewModel()
        scope.launch(Dispatchers.Unconfined) { vm.state.collect { recorded += it } }
        // Primer escaneo: queda bloqueado dentro del proveedor hasta que lo liberemos.
        val firstGate = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)
        tree = FakeTree(listOf(node("a.gb")), mapOf("a.gb" to rom("VIEJO")), gate = firstGate, entered = firstEntered)
        vm.rescan()
        assertTrue("el primer escaneo debe haber entrado en el proveedor", firstEntered.await(5, TimeUnit.SECONDS))
        // Segundo escaneo con otro contenido: cancela al primero, que sigue bloqueado.
        tree = FakeTree(listOf(node("b.gb")), mapOf("b.gb" to rom("NUEVO")))
        vm.rescan()
        firstGate.countDown()
        val ready = await { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready
        assertEquals(listOf("NUEVO"), ready.entries.map { it.title })
        assertTrue(
            "el resultado del primer escaneo no se publica nunca",
            recorded.none { it is LibraryState.Ready && it.entries.any { e -> e.title == "VIEJO" } },
        )
    }

    @Test
    fun forgetFolderWhileAScanIsRunningNeverResurrectsTheFolderState() {
        val recorded = java.util.concurrent.CopyOnWriteArrayList<LibraryState>()
        val vm = viewModel()
        scope.launch(Dispatchers.Unconfined) { vm.state.collect { recorded += it } }
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        tree = FakeTree(listOf(node("a.gb")), mapOf("a.gb" to rom("ALPHA")), gate = gate, entered = entered)
        vm.rescan()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        vm.forgetFolder()
        gate.countDown()
        await { vm.state.first { it == LibraryState.NoFolder } }
        assertTrue(folders.forgotten)
        assertNull(vm.folderName.value)
        assertTrue("un escaneo viejo no publica tras olvidar la carpeta", recorded.none { it is LibraryState.Ready })
    }

    @Test
    fun forgetFolderDuringChooseFolderLeavesNoFolderRemembered() {
        folders.uri = null
        val vm = viewModel()
        val gate = CountDownLatch(1)
        folders.selectGate = gate
        vm.chooseFolder("content://tree/Nueva")
        vm.forgetFolder() // llega mientras `select` sigue en curso
        gate.countDown()
        await { vm.state.first { it == LibraryState.NoFolder } }
        assertNull("la carpeta no se vuelve a persistir tras olvidarla", folders.uri)
    }

    @Test
    fun revokedPermissionOnASubfolderIsNotAPartialLibrary() {
        tree = FakeTree(
            files = listOf(TreeNode("sub", "Sub", isDirectory = true, sizeBytes = 0), node("a.gb")),
            heads = mapOf("a.gb" to rom("ALPHA")),
            dirFailures = mapOf("sub" to TreePermissionException()),
        )
        val vm = viewModel()
        vm.rescan()
        assertEquals(LibraryState.Failed(LibraryError.PermissionRevoked), vm.awaitSettled())
    }

    @Test
    fun providerRuntimeFailuresInDetailsAreTypedNotCrashes() {
        val (vm, entry) = readyVm()
        romBytes = { _, _ -> throw IllegalStateException("proveedor roto") }
        assertEquals(DetailsLoad.Failed(DetailsError.Unreadable), await { vm.loadDetails(entry.id) })
        romBytes = { _, _ -> ByteArray(0x8000) }
        inspector = { throw IllegalArgumentException("raro") }
        assertEquals(DetailsLoad.Failed(DetailsError.Unreadable), await { vm.loadDetails(entry.id) })
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
        // Error propio (no "permiso revocado") y la carpeta anterior sigue recordada.
        await { vm.state.first { it == LibraryState.Failed(LibraryError.AccessNotKept) } }
        assertEquals("content://tree/Nueva", folders.uri)
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
    fun changesMadeWhileTheLoadIsReallyInFlightAreAppliedOnTopOfIt() {
        prefsFile.save(LibraryPreferencesData(favorites = setOf("viejo.gb")))
        val gate = CountDownLatch(1)
        val loading = CountDownLatch(1)
        store.loadHook = { loading.countDown() }
        store.loadGate = gate
        val vm = viewModel()
        val entry = RomEntry("nuevo.gb", "content://x", "nuevo.gb", "N", false, 1, true, null)
        assertTrue("la carga debe estar en curso", loading.await(5, TimeUnit.SECONDS))
        vm.toggleFavorite(entry)
        // Con la carga todavía bloqueada, la memoria ya refleja el cambio y el disco no se ha tocado.
        assertEquals(setOf("nuevo.gb"), vm.prefs.value.favorites)
        assertEquals(setOf("viejo.gb"), prefsFile.load().favorites)
        gate.countDown()
        assertEquals(PersistResult.Saved, await { vm.flushPreferences() })
        assertEquals(setOf("viejo.gb", "nuevo.gb"), vm.prefs.value.favorites)
        assertEquals(setOf("viejo.gb", "nuevo.gb"), prefsFile.load().favorites)
    }

    @Test
    fun aChangeInsideTheLoadWindowCannotLeaveTheDiskBehindMemory() {
        // Reproduce la carrera H1: un cambio llega justo cuando la carga termina de leer.
        prefsFile.save(LibraryPreferencesData(favorites = setOf("viejo.gb")))
        lateinit var vm: LibraryViewModel
        val created = CountDownLatch(1)
        store.loadHook = {
            created.await(5, TimeUnit.SECONDS)
            vm.setLayout(LibraryLayout.LIST)
        }
        vm = viewModel()
        created.countDown()
        assertEquals(PersistResult.Saved, await { vm.flushPreferences() })
        assertEquals(LibraryLayout.LIST, vm.prefs.value.layout)
        assertEquals(vm.prefs.value, prefsFile.load())
    }

    @Test
    fun concurrentChangesDuringLoadAlwaysEndUpInTheFile() {
        repeat(150) { round ->
            val file = LibraryPreferencesFile(File(temp.root, "stress/$round.json"))
            file.save(LibraryPreferencesData(favorites = setOf("base")))
            val flaky = FlakyStore(file)
            val vm = LibraryViewModel(
                folders = folders,
                openTree = { tree },
                roms = RomSource { uri, limit -> romBytes(uri, limit) },
                inspector = { inspector(it) },
                preferencesFile = flaky,
                io = Dispatchers.IO,
                scope = scope,
            )
            val start = CountDownLatch(1)
            val workers = (0 until 4).map { worker ->
                Thread {
                    start.await()
                    repeat(10) { i ->
                        vm.toggleFavorite(RomEntry("w$worker-$i", "u", "f", "t", false, 1, true, null))
                        if (i % 3 == 0) Thread.yield()
                    }
                }.also { it.start() }
            }
            start.countDown()
            workers.forEach { it.join() }
            assertEquals(PersistResult.Saved, await { vm.flushPreferences() })
            assertEquals("ronda $round", vm.prefs.value, file.load())
            assertEquals(41, vm.prefs.value.favorites.size)
        }
    }

    @Test
    fun preferenceWriteFailureStaysPendingAndFlushReportsIt() {
        val vm = viewModel()
        assertEquals(PersistResult.Saved, await { vm.flushPreferences() })
        val entry = RomEntry("x.gb", "u", "x.gb", "X", false, 1, true, null)
        // Dos intentos fallan: el de la señal del propio cambio y el del flush.
        store.saveFailures = 2
        vm.toggleFavorite(entry)
        val failed = await { vm.flushPreferences() }
        assertTrue(failed is PersistResult.Failed)
        assertEquals(emptySet<String>(), prefsFile.load().favorites)
        assertEquals(setOf("x.gb"), vm.prefs.value.favorites)
        // Reintento al parar la actividad.
        vm.retryPendingWrites()
        assertEquals(PersistResult.Saved, await { vm.flushPreferences() })
        assertEquals(setOf("x.gb"), prefsFile.load().favorites)
    }

    @Test
    fun pendingWriteIsRetriedByTheBlockingFinalWrite() {
        val vm = viewModel()
        assertEquals(PersistResult.Saved, await { vm.flushPreferences() })
        store.saveFailures = 2 // la señal del cambio y el flush
        vm.setLayout(LibraryLayout.LIST)
        assertTrue(await { vm.flushPreferences() } is PersistResult.Failed)
        // Es lo que ejecuta onCleared(): ya no habrá "siguiente cambio" que reintente.
        assertEquals(PersistResult.Saved, vm.persistBlocking())
        assertEquals(LibraryLayout.LIST, prefsFile.load().layout)
    }

    @Test
    fun loadIoFailureNeverOverwritesTheFileWithDefaults() {
        prefsFile.save(LibraryPreferencesData(favorites = setOf("viejo.gb")))
        store.loadFailures = 3 // carga inicial, señal del cambio y flush
        val vm = viewModel()
        vm.toggleFavorite(RomEntry("nuevo.gb", "u", "n", "N", false, 1, true, null))
        assertTrue(await { vm.flushPreferences() } is PersistResult.Failed)
        assertEquals("el archivo no se toca", setOf("viejo.gb"), prefsFile.load().favorites)
        // Cuando el disco responde, se carga lo que había y se le suma el cambio pendiente.
        assertEquals(PersistResult.Saved, await { vm.flushPreferences() })
        assertEquals(setOf("viejo.gb", "nuevo.gb"), prefsFile.load().favorites)
        assertEquals(setOf("viejo.gb", "nuevo.gb"), vm.prefs.value.favorites)
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

    // ---- A6-L3: progreso, nuevos y resumen ----

    @Test
    fun scanPublishesRealProgressAsDoneOfTotal() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()
        val vm = viewModel()
        scope.launch(Dispatchers.Unconfined) {
            vm.state.collect { if (it is LibraryState.Scanning) seen += it.done to it.total }
        }
        vm.rescan()
        vm.awaitSettled()
        assertTrue("debe publicar el último avance 2 de 2: $seen", seen.contains(2 to 2))
        assertTrue(seen.all { (done, total) -> done <= total })
    }

    @Test
    fun firstScanOfAFolderNeverMarksGamesAsNewAndLaterScansDo() {
        val vm = viewModel()
        vm.rescan()
        val first = vm.awaitSettled() as LibraryState.Ready
        assertTrue(first.entries.none { it.isNew })
        assertEquals(0, vm.newGamesSummary.value)
        // Aparece un juego nuevo en la carpeta.
        tree = FakeTree(
            listOf(node("b.gb"), node("a.gb"), node("c.gb")),
            mapOf("a.gb" to rom("ALPHA"), "b.gb" to rom("BETA"), "c.gb" to rom("GAMMA")),
        )
        vm.rescan()
        val second = await { vm.state.first { it is LibraryState.Ready && it.entries.size == 3 } } as LibraryState.Ready
        assertEquals(listOf("GAMMA"), second.entries.filter { it.isNew }.map { it.title })
        assertEquals(1, vm.newGamesSummary.value)
        vm.dismissNewGamesSummary()
        assertEquals(0, vm.newGamesSummary.value)
    }

    @Test
    fun openingANewGameClearsItsNewMarkAndItStaysClearedAfterARescan() {
        val vm = viewModel()
        vm.rescan()
        vm.awaitSettled()
        tree = FakeTree(
            listOf(node("a.gb"), node("c.gb")),
            mapOf("a.gb" to rom("ALPHA"), "c.gb" to rom("GAMMA")),
        )
        vm.rescan()
        val ready = await { vm.state.first { it is LibraryState.Ready && it.entries.any { e -> e.isNew } } } as LibraryState.Ready
        val gamma = ready.entries.first { it.isNew }
        vm.recordPlayed(gamma, "%064x".format(3), at = 10)
        val cleared = vm.state.value as LibraryState.Ready
        assertTrue(cleared.entries.none { it.isNew })
        vm.rescan()
        val again = await { vm.state.first { it is LibraryState.Ready && it !== cleared } } as LibraryState.Ready
        assertTrue(again.entries.none { it.isNew })
    }

    @Test
    fun aRemovedGameIsForgottenSoItIsNewAgainIfItReappears() {
        val vm = viewModel()
        vm.rescan()
        vm.awaitSettled()
        // B desaparece de la carpeta (borrado o renombrado): el escaneo completo lo olvida.
        tree = FakeTree(listOf(node("a.gb")), mapOf("a.gb" to rom("ALPHA")))
        vm.rescan()
        await { vm.state.first { it is LibraryState.Ready && it.entries.size == 1 } }
        // Reaparece: ya no estaba en el escaneo anterior, así que es «Nuevo».
        tree = FakeTree(
            listOf(node("b.gb"), node("a.gb")),
            mapOf("a.gb" to rom("ALPHA"), "b.gb" to rom("BETA")),
        )
        vm.rescan()
        val back = await { vm.state.first { it is LibraryState.Ready && it.entries.size == 2 } } as LibraryState.Ready
        assertEquals(listOf("BETA"), back.entries.filter { it.isNew }.map { it.title })
    }

    @Test
    fun choosingAnotherFolderRestartsTheKnownGames() {
        val vm = viewModel()
        vm.rescan()
        vm.awaitSettled()
        tree = FakeTree(listOf(node("z.gb")), mapOf("z.gb" to rom("ZETA")))
        vm.chooseFolder("content://tree/Otra")
        val ready = await { vm.state.first { it is LibraryState.Ready && it.entries.any { e -> e.title == "ZETA" } } } as LibraryState.Ready
        assertTrue("el primer escaneo de otra carpeta no marca nada", ready.entries.none { it.isNew })
    }
}
