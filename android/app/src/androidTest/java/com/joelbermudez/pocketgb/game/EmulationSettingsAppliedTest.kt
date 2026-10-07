package com.joelbermudez.pocketgb.game

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsFile
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A6-H1: los ajustes de emulación (Color para Game Boy, paleta y la hoja por juego) llegan al núcleo al abrir.
 * ViewModel + repositorio de ajustes reales; solo la ROM es sintética.
 */
@RunWith(AndroidJUnit4::class)
class EmulationSettingsAppliedTest {
    private lateinit var root: File
    private lateinit var settings: GameplaySettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        root = tempDir("emulation-applied")
        settings = GameplaySettingsRepository(GameplaySettingsFile(File(root, "gameplay-settings.json")), scope)
    }

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        root.deleteRecursively()
    }

    private fun fingerprint(rom: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(rom).joinToString("") { "%02x".format(it) }

    private fun update(change: (com.joelbermudez.pocketgb.settings.GameplaySettingsData) -> com.joelbermudez.pocketgb.settings.GameplaySettingsData) =
        runBlocking { settings.update(change).join() }

    /** Abre por el ViewModel (camino de la app) y devuelve la sesión; la cierra el llamador mediante [closeAll]. */
    private val vms = mutableListOf<GameplayViewModel>()

    private fun openWith(rom: ByteArray): GameSession {
        val vm = GameplayViewModel(
            GameplayTestHost.launcher(File(root, "r${vms.size}"), rom = rom, emulationFor = settings.emulationProvider()),
        )
        vms += vm
        vm.open(GameplayTestHost.entry)
        assertTrue(waitUntil(15_000) { vm.game.value != null })
        return vm.game.value!!
    }

    @After
    fun closeAll() {
        vms.forEach { runCatching { it.game.value?.close() } }
    }

    @Test
    fun globalColorAndPaletteAreAppliedOnOpen() {
        update { it.copy(colorForGameBoy = true, compatPalette = 5) }
        val game = openWith(SyntheticRom.sramCounter())
        assertTrue(game.info.cgbCompat)
        assertEquals(5, game.session.compatPalette)
    }

    @Test
    fun perGameOverrideWithoutColorWinsOverTheGlobalColor() {
        val rom = SyntheticRom.sramCounter()
        update {
            it.copy(
                colorForGameBoy = true,
                compatPalette = 5,
                perGame = mapOf(fingerprint(rom) to GameOverrides(colorForGameBoy = false)),
            )
        }
        val game = openWith(rom)
        assertFalse(game.info.cgbCompat)
        assertFalse(game.info.cgbMode)
    }

    @Test
    fun perGamePaletteWinsOverTheGlobalOne() {
        val rom = SyntheticRom.sramCounter()
        update {
            it.copy(colorForGameBoy = true, compatPalette = 5, perGame = mapOf(fingerprint(rom) to GameOverrides(compatPalette = 9)))
        }
        assertEquals(9, openWith(rom).session.compatPalette)
    }

    @Test
    fun gbcRomAlwaysOpensWithAutoModel() {
        update { it.copy(colorForGameBoy = false) } // un DMG forzado rechazaría un cartucho solo-CGB
        val game = openWith(SyntheticRom.withCgbFlag(SyntheticRom.sramCounter(), 0xC0))
        assertTrue(game.info.cgbMode)
        assertFalse(game.info.cgbCompat)
    }
}
