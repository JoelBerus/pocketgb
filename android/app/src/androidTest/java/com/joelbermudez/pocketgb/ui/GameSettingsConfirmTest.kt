package com.joelbermudez.pocketgb.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.emulator.RomInfo
import com.joelbermudez.pocketgb.library.DocumentTree
import com.joelbermudez.pocketgb.library.FolderGrant
import com.joelbermudez.pocketgb.library.FolderStore
import com.joelbermudez.pocketgb.library.LibraryPreferencesData
import com.joelbermudez.pocketgb.library.LibraryPreferencesFile
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.TreeNode
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsFile
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.ui.details.GameSettingsHost
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N1-H1: los ajustes del juego nunca se escriben en una huella heredada de un movimiento sin confirmar. Si la huella de
 * la ruta salió del sello (sin leer el ROM), la hoja la confirma leyendo el ROM antes de mostrar o guardar ajustes.
 */
@RunWith(AndroidJUnit4::class)
class GameSettingsConfirmTest {
    @get:Rule val compose = createComposeRule()

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "n1-settings-confirm")
        .apply { deleteRecursively(); mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inherited = "aa".repeat(32) // la de otro juego, heredada por error
    private val real = "22".repeat(32) // la del ROM de verdad

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun header(title: String): ByteArray {
        val bytes = ByteArray(0x150)
        title.forEachIndexed { i, c -> bytes[0x134 + i] = c.code.toByte() }
        var x = 0
        for (i in 0x134..0x14C) x = (x - (bytes[i].toInt() and 0xFF) - 1) and 0xFF
        bytes[0x14D] = x.toByte()
        return bytes
    }

    @Test
    fun anInheritedFingerprintIsConfirmedBeforeAnySettingIsWritten() {
        val tree = object : DocumentTree {
            override fun children(directoryId: String?) =
                if (directoryId == null) listOf(TreeNode("rojo", "Rojo.gb", false, 32768, lastModified = 5L)) else emptyList()
            override fun readHead(node: TreeNode, limit: Int) = header("RED")
            override fun uriOf(node: TreeNode) = "content://t/${node.id}"
        }
        val folders = object : FolderStore {
            override fun currentUri() = "content://t/tree"
            override fun hasPersistedPermission() = true
            override fun select(uri: String) = FolderGrant(false)
            override fun forget() {}
            override fun displayName() = "Roms"
        }
        val prefsFile = LibraryPreferencesFile(File(dir, "preferences.json"))
        prefsFile.save(LibraryPreferencesData(fingerprints = mapOf("Rojo.gb" to inherited), inferredFingerprints = setOf("Rojo.gb")))
        val vm = LibraryViewModel(
            folders = folders,
            openTree = { tree },
            roms = { _, _ -> ByteArray(0x8000) },
            inspector = {
                RomInfo(
                    title = "RED", cgbFlag = 0, cartType = 0x13, romBytes = 32768, sramBytes = 8192, hasBattery = true,
                    hasRtc = false, headerChecksumOk = true, globalChecksumOk = true, cgbMode = false, cgbCompat = false,
                    fingerprint = ByteArray(32) { 0x22 },
                )
            },
            preferencesFile = prefsFile,
            io = Dispatchers.IO,
            scope = scope,
        )
        vm.rescan()
        val entry = runBlocking { withTimeout(10_000) { vm.state.first { it is LibraryState.Ready } } as LibraryState.Ready }
            .entries.single()
        val repository = GameplaySettingsRepository(GameplaySettingsFile(File(dir, "gameplay-settings.json")), scope)

        compose.setContent { PocketGBTheme { GameSettingsHost(entry, vm, repository, onDismiss = {}) } }
        compose.waitUntil(10_000) { vm.prefs.value.fingerprints[entry.id] == real && entry.id !in vm.prefs.value.inferredFingerprints }
        // N4: en el centro de ajustes el color va más abajo (tras categoría, etiquetas…).
        compose.onNodeWithTag("game-setting-color").performScrollTo().performClick()
        compose.onNodeWithTag("game-setting-color-1").performClick()
        compose.waitUntil(10_000) { repository.state.value.perGame[real] != null }
        assertEquals(GameOverrides(colorForGameBoy = true), repository.state.value.perGame[real])
        assertNull("nada en la huella heredada", repository.state.value.perGame[inherited])
        assertTrue(vm.prefs.value.hasConfirmedFingerprint(entry))
    }
}
