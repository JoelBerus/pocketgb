package com.joelbermudez.pocketgb.library

import com.joelbermudez.pocketgb.emulator.RomInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N1a + N1b con el ViewModel real: mover un ROM entre subcarpetas profundas entre dos escaneos conserva favorito, alias y
 * oculto sin leer el ROM, no lo marca como «Nuevo», y un escaneo incompleto no olvida nada.
 */
class LibraryMoveViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class Folders : FolderStore {
        override fun currentUri() = "content://tree/Roms"
        override fun hasPersistedPermission() = true
        override fun select(uri: String) = FolderGrant(writeGranted = true)
        override fun forget() {}
        override fun displayName() = "Roms"
    }

    /** Árbol que se puede reorganizar entre escaneos: carpeta → hijos. Ids como los de ExternalStorage (la ruta). */
    private class MovableTree : DocumentTree {
        @Volatile var dirs: Map<String?, List<TreeNode>> = emptyMap()
        @Volatile var failing: Set<String> = emptySet()
        /** Carpetas que el proveedor da «aún cargando» (EXTRA_LOADING) las próximas N veces. */
        val loadingFor = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val heads = HashMap<String, ByteArray>()
        val headReads = AtomicInteger()
        override fun children(directoryId: String?): List<TreeNode> {
            if (directoryId in failing) throw IOException("Drive sin red")
            val pending = directoryId?.let { loadingFor[it] } ?: 0
            if (pending > 0) {
                loadingFor[directoryId!!] = pending - 1
                throw PartialListingException(emptyList(), "cargando", loading = true)
            }
            return dirs[directoryId].orEmpty()
        }
        override fun readHead(node: TreeNode, limit: Int): ByteArray {
            headReads.incrementAndGet()
            return heads.getValue(node.name).copyOf(limit)
        }
        override fun uriOf(node: TreeNode) = "content://tree/${node.id}"
    }

    private fun header(title: String): ByteArray {
        val bytes = ByteArray(0x150)
        title.forEachIndexed { i, c -> bytes[0x134 + i] = c.code.toByte() }
        var x = 0
        for (i in 0x134..0x14C) x = (x - (bytes[i].toInt() and 0xFF) - 1) and 0xFF
        bytes[0x14D] = x.toByte()
        return bytes
    }

    private fun dir(id: String) = TreeNode(id, id.substringAfterLast('/'), isDirectory = true, sizeBytes = 0)
    private fun rom(id: String, size: Long = 1_048_576, modified: Long? = 1_700_000_000_000) =
        TreeNode(id, id.substringAfterLast('/'), isDirectory = false, sizeBytes = size, lastModified = modified)

    private val tree = MovableTree().apply {
        heads["Pokemon Red.gb"] = header("POKEMON RED")
        heads["Tetris.gb"] = header("TETRIS")
    }
    private val romReads = AtomicInteger()
    private val prefsFile get() = LibraryPreferencesFile(File(temp.root, "preferences.json"))
    private val fingerprintByte: Byte = 0x42
    private val fingerprint = "42".repeat(32)

    private fun viewModel(scans: MutableList<ScanStats> = mutableListOf(), retryDelayMs: Long = 3_000L) = LibraryViewModel(
        folders = Folders(),
        openTree = { tree },
        roms = RomSource { _, _ ->
            romReads.incrementAndGet()
            ByteArray(0x8000)
        },
        inspector = {
            RomInfo(
                title = "POKEMON RED", cgbFlag = 0, cartType = 0x13, romBytes = 1_048_576, sramBytes = 32768,
                hasBattery = true, hasRtc = false, headerChecksumOk = true, globalChecksumOk = true,
                cgbMode = false, cgbCompat = false, fingerprint = ByteArray(32) { fingerprintByte },
            )
        },
        preferencesFile = prefsFile,
        io = Dispatchers.IO,
        scope = scope,
        scanLog = { synchronized(scans) { scans += it } },
        loadingRetryDelayMs = retryDelayMs,
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun LibraryViewModel.scanNow(): LibraryState.Ready {
        val before = state.value
        rescan()
        return await { state.first { it is LibraryState.Ready && it !== before } } as LibraryState.Ready
    }

    /** `Roms/Pokémon/Gen 1/Pokemon Red.gb` y `Roms/Tetris.gb`. */
    private fun layoutBefore() {
        tree.dirs = mapOf(
            null to listOf(dir("Pokémon"), rom("Tetris.gb", size = 32768)),
            "Pokémon" to listOf(dir("Pokémon/Gen 1")),
            "Pokémon/Gen 1" to listOf(rom("Pokémon/Gen 1/Pokemon Red.gb")),
        )
    }

    /** El ROM pasa a `Roms/Clásicos/Nintendo/Pokémon/Rojo/Kanto/Pokemon Red.gb` (5 niveles). */
    private fun layoutAfter() {
        tree.dirs = mapOf(
            null to listOf(dir("Clásicos"), rom("Tetris.gb", size = 32768)),
            "Clásicos" to listOf(dir("Clásicos/Nintendo")),
            "Clásicos/Nintendo" to listOf(dir("Clásicos/Nintendo/Pokémon")),
            "Clásicos/Nintendo/Pokémon" to listOf(dir("Clásicos/Nintendo/Pokémon/Rojo")),
            "Clásicos/Nintendo/Pokémon/Rojo" to listOf(dir("Clásicos/Nintendo/Pokémon/Rojo/Kanto")),
            "Clásicos/Nintendo/Pokémon/Rojo/Kanto" to listOf(rom("Clásicos/Nintendo/Pokémon/Rojo/Kanto/Pokemon Red.gb")),
        )
    }

    @Test
    fun movingARomToADeepFolderKeepsItsFavoriteAliasAndHiddenStateWithoutReadingIt() {
        layoutBefore()
        val scans = mutableListOf<ScanStats>()
        val vm = viewModel(scans)
        val first = vm.scanNow()
        val red = first.entries.single { it.title == "POKEMON RED" }
        assertEquals(listOf("Pokémon", "Gen 1"), red.folderPath)
        await { vm.loadDetails(red.id) } // la huella se conoce al ver el detalle (lee el ROM una vez)
        vm.toggleFavorite(red)
        vm.setAlias(red, "Rojo de Joel")
        vm.hide(red)
        assertEquals(1, romReads.get())

        layoutAfter()
        val second = vm.scanNow()
        val moved = second.entries.single { it.title == "POKEMON RED" }
        assertEquals("Clásicos/Nintendo/Pokémon/Rojo/Kanto/Pokemon Red.gb", moved.id)
        assertEquals(listOf("Clásicos", "Nintendo", "Pokémon", "Rojo", "Kanto"), moved.folderPath)
        assertEquals("no se leyó el ROM para reconocerlo", 1, romReads.get())
        val prefs = vm.prefs.value
        assertEquals(fingerprint, prefs.fingerprints[moved.id])
        assertTrue(prefs.isFavorite(moved))
        assertEquals("Rojo de Joel", prefs.displayTitle(moved))
        assertTrue(prefs.isHidden(moved))
        assertFalse("un movimiento reconocido no es «Nuevo»", moved.isNew)
        assertEquals(0, vm.newGamesSummary.value)
        assertTrue(scans.last().complete)
        assertEquals(scans.last(), vm.lastScan.value)

        // Y queda en disco: otra instancia lo lee igual.
        assertTrue(await { vm.flushPreferences() } is PersistResult.Saved)
        val reloaded = prefsFile.load()
        assertEquals(fingerprint, reloaded.fingerprints[moved.id])
        assertEquals(LibraryPreferencesFormat.CURRENT, reloaded.formatVersion)
    }

    @Test
    fun anIncompleteRescanForgetsNothingAndMarksNothingAsNew() {
        layoutBefore()
        val vm = viewModel()
        val red = vm.scanNow().entries.single { it.title == "POKEMON RED" }
        await { vm.loadDetails(red.id) }
        vm.toggleFavorite(red)

        // La subcarpeta falla (Drive sin red): el juego no aparece, pero no se olvida su huella ni su sello.
        tree.failing = setOf("Pokémon/Gen 1")
        val partial = vm.scanNow()
        assertTrue(partial.entries.none { it.title == "POKEMON RED" })
        assertFalse(vm.lastScan.value!!.complete)
        assertEquals(fingerprint, vm.prefs.value.fingerprints[red.id])
        assertTrue(red.id in vm.prefs.value.knownIds)

        tree.failing = emptySet()
        val back = vm.scanNow().entries.single { it.title == "POKEMON RED" }
        assertFalse(back.isNew)
        assertTrue(vm.prefs.value.isFavorite(back))
    }

    @Test
    fun aMoveThatCannotBeRecognisedIsRecoveredWhenTheDetailsAreOpened() {
        // El proveedor no da la fecha: sin sello completo no hay traslado, pero lo guardado por huella vuelve al leerlo.
        tree.dirs = mapOf(null to listOf(dir("A")), "A" to listOf(rom("A/Pokemon Red.gb", modified = null)))
        val vm = viewModel()
        val red = vm.scanNow().entries.single()
        await { vm.loadDetails(red.id) }
        vm.toggleFavorite(red)
        tree.dirs = mapOf(null to listOf(dir("B")), "B" to listOf(rom("B/Pokemon Red.gb", modified = null)))
        val moved = vm.scanNow().entries.single()
        assertFalse(vm.prefs.value.isFavorite(moved))
        await { vm.loadDetails(moved.id) }
        assertTrue(vm.prefs.value.isFavorite(moved))
        assertEquals(2, romReads.get())
    }

    @Test
    fun anUnchangedRomIsNotReopenedOnTheNextScan() {
        // N1-H6: la cabecera se toma de la caché por (id de documento, tamaño, fecha).
        layoutBefore()
        val scans = mutableListOf<ScanStats>()
        val vm = viewModel(scans)
        vm.scanNow()
        assertEquals(2, tree.headReads.get())
        vm.scanNow()
        assertEquals("ningún ROM se vuelve a abrir", 2, tree.headReads.get())
        assertEquals(2, scans.last().headerCacheHits)
        assertEquals(0, scans.last().headReads)
        // Movido: su id de documento (la ruta) cambia, así que se lee una vez (y se reconoce por el sello).
        layoutAfter()
        val moved = vm.scanNow().entries.single { it.title == "POKEMON RED" }
        assertEquals(3, tree.headReads.get())
        assertTrue(moved.id.startsWith("Clásicos/"))
    }

    @Test
    fun preferencesFromAFutureVersionAreUsedButNeverWritten() {
        // N1-H2: un preferences.json de una versión más nueva no se sobrescribe; la UI lo avisa.
        // N4: la 4 ya es la actual; la futura es la 5.
        val original = """{"formatVersion":5,"favoriteFingerprints":["$fingerprint"],"categorias":{"Pokémon":["x"]}}"""
        File(temp.root, "preferences.json").writeText(original)
        layoutBefore()
        val vm = viewModel()
        val red = vm.scanNow().entries.single { it.title == "POKEMON RED" }
        assertTrue(vm.preferencesReadOnly.value)
        await { vm.loadDetails(red.id) }
        assertTrue("se ve lo que esta versión entiende", vm.prefs.value.isFavorite(red))
        vm.setAlias(red, "Rojo de Joel")
        assertTrue(await { vm.flushPreferences() } is PersistResult.Failed)
        assertEquals("Rojo de Joel", vm.prefs.value.displayTitle(red))
        assertEquals("el archivo no se toca", original, File(temp.root, "preferences.json").readText())
    }

    @Test
    fun aFolderStillLoadingIsScannedAgainShortlyWithoutTheUserAsking() {
        // N1-V2: Drive da la carpeta «aún cargando» dos veces; la app reintenta sola y acaba viendo el juego.
        layoutBefore()
        tree.loadingFor["Pokémon/Gen 1"] = 2
        val scans = mutableListOf<ScanStats>()
        val vm = viewModel(scans, retryDelayMs = 50L)
        vm.rescan()
        val ready = await { vm.state.first { it is LibraryState.Ready && it.entries.any { e -> e.title == "POKEMON RED" } } }
        assertTrue(ready is LibraryState.Ready)
        assertEquals(3, synchronized(scans) { scans.size })
        assertTrue(vm.lastScan.value!!.complete)
    }

    @Test
    fun loadingRetriesStopAfterTheLimit() {
        layoutBefore()
        tree.loadingFor["Pokémon/Gen 1"] = 1_000
        val scans = mutableListOf<ScanStats>()
        val vm = viewModel(scans, retryDelayMs = 20L)
        vm.rescan()
        await { vm.state.first { it is LibraryState.Ready } }
        val deadline = System.currentTimeMillis() + 10_000
        while (synchronized(scans) { scans.size } < 1 + LibraryViewModel.MAX_LOADING_RETRIES && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        Thread.sleep(500) // un reintento de más ya habría llegado
        assertEquals("el escaneo y tres reintentos", 1 + LibraryViewModel.MAX_LOADING_RETRIES, synchronized(scans) { scans.size })
        assertEquals(1 + LibraryViewModel.MAX_LOADING_RETRIES, synchronized(scans) { scans.count { it.loadingFolders == 1 } })
    }
}
