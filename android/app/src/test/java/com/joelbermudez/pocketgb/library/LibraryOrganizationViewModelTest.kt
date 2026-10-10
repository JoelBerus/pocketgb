package com.joelbermudez.pocketgb.library

import com.joelbermudez.pocketgb.emulator.RomInfo
import java.io.File
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
 * N4 con el ViewModel real y `preferences.json` en disco: etiquetas y categoría virtual solo se escriben con la huella
 * confirmada (se confirma leyendo el ROM), van por huella (sobreviven a mover el archivo) y se guardan en el formato 4
 * junto con los ajustes del inicio y la vista por categoría.
 */
class LibraryOrganizationViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class Tree : DocumentTree {
        @Volatile var dirs: Map<String?, List<TreeNode>> = emptyMap()
        override fun children(directoryId: String?): List<TreeNode> = dirs[directoryId].orEmpty()
        override fun readHead(node: TreeNode, limit: Int): ByteArray = header(node.name).copyOf(limit)
        override fun uriOf(node: TreeNode) = "content://tree/${node.id}"

        private fun header(name: String): ByteArray {
            val bytes = ByteArray(0x150)
            name.uppercase().take(15).forEachIndexed { i, c -> bytes[0x134 + i] = c.code.toByte() }
            var x = 0
            for (i in 0x134..0x14C) x = (x - (bytes[i].toInt() and 0xFF) - 1) and 0xFF
            bytes[0x14D] = x.toByte()
            return bytes
        }
    }

    private val tree = Tree()
    private val file get() = File(temp.root, "preferences.json")
    private val fingerprint = "42".repeat(32)

    private fun dir(id: String) = TreeNode(id, id.substringAfterLast('/'), isDirectory = true, sizeBytes = 0)
    private fun rom(id: String) = TreeNode(id, id.substringAfterLast('/'), isDirectory = false, sizeBytes = 1_048_576, lastModified = 1_700_000_000_000)

    private fun viewModel() = LibraryViewModel(
        folders = object : FolderStore {
            override fun currentUri() = "content://tree/Roms"
            override fun hasPersistedPermission() = true
            override fun select(uri: String) = FolderGrant(writeGranted = true)
            override fun forget() {}
            override fun displayName() = "Roms"
        },
        openTree = { tree },
        roms = RomSource { _, _ -> ByteArray(0x8000) },
        inspector = { _, _ ->
            RomInfo(
                title = "POKEMON RED", cgbFlag = 0, cartType = 0x13, romBytes = 1_048_576, sramBytes = 32768,
                hasBattery = true, hasRtc = false, headerChecksumOk = true, globalChecksumOk = true,
                cgbMode = false, cgbCompat = false, fingerprint = ByteArray(32) { 0x42 },
            )
        },
        preferencesFile = LibraryPreferencesFile(file),
        io = Dispatchers.IO,
        scope = scope,
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun LibraryViewModel.scanNow(): LibraryState.Ready {
        val before = state.value
        rescan()
        return await { state.first { it is LibraryState.Ready && it !== before } } as LibraryState.Ready
    }

    private fun layout(folder: String) {
        tree.dirs = mapOf(
            null to listOf(dir(folder)),
            folder to listOf(rom("$folder/Pokemon Red.gb")),
        )
    }

    @Test
    fun tagsAndTheVirtualCategoryNeedAConfirmedFingerprintAndArePersistedByFingerprint() {
        layout("Pokémon")
        val vm = viewModel()
        val red = vm.scanNow().entries.single()
        assertFalse("sin huella no se escribe", vm.addTag(red, "rpg"))
        assertFalse(vm.moveToCategory(red, listOf("Favoritas")))
        assertTrue(vm.prefs.value.tagsByFingerprint.isEmpty())
        assertTrue("se confirma leyendo el ROM", await { vm.confirmFingerprint(red) })
        assertTrue(vm.addTag(red, "rpg"))
        assertTrue(vm.moveToCategory(red, listOf("Favoritas")))
        vm.updateHome { it.withHidden("Pokémon", true) }
        vm.setCategoryLayout(LibraryCategory.Folder("Favoritas"), LibraryLayout.LIST)
        assertTrue(await { vm.flushPreferences() } is PersistResult.Saved)
        val text = file.readText()
        assertTrue(text.contains("\"formatVersion\":4"))
        val onDisk = LibraryPreferencesFile(file).load()
        assertEquals(mapOf(fingerprint to listOf("rpg")), onDisk.tagsByFingerprint)
        assertEquals(mapOf(fingerprint to listOf("Favoritas")), onDisk.virtualFoldersByFingerprint)
        assertEquals(setOf("Pokémon"), onDisk.home.hidden)
        assertEquals(LibraryLayout.LIST, onDisk.layoutFor(LibraryCategory.Folder("Favoritas")))
    }

    @Test
    fun aMovedRomKeepsItsTagsAndCategoryButAnInheritedFingerprintMustBeConfirmedBeforeWriting() {
        layout("Pokémon")
        val vm = viewModel()
        val red = vm.scanNow().entries.single()
        await { vm.confirmFingerprint(red) }
        vm.addTag(red, "rpg")
        vm.moveToCategory(red, listOf("Favoritas"))
        // Joel mueve el archivo de carpeta en Drive: se reconoce por su sello (sin leerlo) y hereda la huella.
        layout("Clásicos")
        val moved = vm.scanNow().entries.single()
        assertEquals(listOf("Clásicos"), moved.folderPath)
        val prefs = vm.prefs.value
        assertFalse("heredada, sin confirmar", prefs.hasConfirmedFingerprint(moved))
        val shown = LibraryQuery.presented(listOf(moved), prefs, moved.id)!!
        assertEquals("se sigue viendo con sus etiquetas", listOf("rpg"), shown.tags)
        assertEquals("y en su categoría virtual", listOf("Favoritas"), shown.categoryPath)
        assertFalse("pero no se escribe sobre una huella heredada", vm.addTag(moved, "otra"))
        assertTrue(await { vm.confirmFingerprint(moved) })
        assertTrue(vm.returnToFolder(moved))
        assertEquals(listOf("Clásicos"), LibraryQuery.presented(listOf(moved), vm.prefs.value, moved.id)!!.categoryPath)
    }
}
