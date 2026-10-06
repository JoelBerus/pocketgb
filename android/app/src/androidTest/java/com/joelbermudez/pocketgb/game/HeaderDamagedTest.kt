package com.joelbermudez.pocketgb.game

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.emulator.EmulationOptions
import com.joelbermudez.pocketgb.emulator.GbModel
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.tempDir
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** K15 y opciones de emulación al abrir: aviso «Cabecera dañada» (se juega igual) y modelo/paleta aplicados. */
@RunWith(AndroidJUnit4::class)
class HeaderDamagedTest {
    private lateinit var root: File
    private val opened = mutableListOf<GameSession>()

    @Before
    fun setUp() {
        root = tempDir("header-damaged")
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        root.deleteRecursively()
    }

    private fun open(rom: ByteArray, options: EmulationOptions = EmulationOptions()): OpenResult.Opened =
        (GameplayTestHost.launcher(root, rom = rom).openBlocking(GameplayTestHost.entry, options) as OpenResult.Opened)
            .also { opened += it.game }

    @Test
    fun badHeaderChecksumOpensWithTheHeaderDamagedNotice() {
        val result = open(SyntheticRom.withBadHeaderChecksum(SyntheticRom.sramCounter()))
        assertEquals(listOf<GameNotice>(GameNotice.HeaderDamaged), result.notices)
        assertFalse(result.game.info.headerChecksumOk)
        result.game.start() // se puede jugar igual
        assertTrue(waitUntilFrames(result.game))
    }

    @Test
    fun goodHeaderHasNoNotices() {
        assertTrue(open(SyntheticRom.sramCounter()).notices.isEmpty())
    }

    @Test
    fun launcherAppliesTheEmulationOptions() {
        val result = open(SyntheticRom.romOnly(), EmulationOptions(GbModel.CGB, compatPalette = 4))
        assertTrue(result.game.info.cgbCompat)
        assertEquals(4, result.game.session.compatPalette)
        result.game.setCompatPalette(7)
        assertEquals(7, result.game.session.compatPalette)
    }

    private fun waitUntilFrames(game: GameSession): Boolean {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (game.session.frameCount < 3 && System.nanoTime() < deadline) Thread.sleep(10)
        return game.session.frameCount >= 3
    }
}
