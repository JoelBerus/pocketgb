package com.joelbermudez.pocketgb.covers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.emulator.CoreBridge
import com.joelbermudez.pocketgb.library.RomConsole
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.library.artwork.CoverChoice
import com.joelbermudez.pocketgb.library.artwork.CoverKind
import com.joelbermudez.pocketgb.library.artwork.CoverRepository
import com.joelbermudez.pocketgb.library.artwork.CoverSettingsStore
import com.joelbermudez.pocketgb.saves.CloseResult
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.openGame
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** N5 · «Usar como portada» desde la pausa con una sesión real: fija la escena, no toca la partida y el cierre no la pisa. */
@RunWith(AndroidJUnit4::class)
class CoverPinTest {
    private lateinit var root: File

    @Before fun setUp() { root = tempDir("cover-pin") }

    @After fun tearDown() { root.deleteRecursively() }

    private fun repository() = CoverRepository(
        captures = ArtworkStore(File(root, "artwork"), executor = null),
        pinned = ArtworkStore(File(root, "artwork-pinned"), executor = null),
        settings = CoverSettingsStore(File(root, "covers/settings.json")),
    )

    @Test
    fun pinningThePausedFrameChoosesCaptureAndSurvivesTheCloseCapture() {
        openGame(SyntheticRom.sramCounter()).use { g ->
            val game = g.game
            val repo = repository()
            // La ROM sintética deja la pantalla lisa: la captura del cierre se marca para que no sea uniforme.
            game.parkedFrameCallback = { pixels -> repo.captures.save(game.fingerprint, pixels.copyOf().also { it[0] = it[0] xor 0xFFFFFF }) }
            game.start()
            assertNull(game.pausedFrame()) // en marcha no hay fotograma fijo
            val before = game.session.sramDirtySequence()
            assertTrue(waitUntil { game.session.sramDirtySequence() > before + 2 })
            game.pause()
            val dirty = game.session.sramDirtySequence()
            val frame = game.pausedFrame()
            assertNotNull(frame)
            assertEquals(CoreBridge.FRAME_PIXELS, frame!!.size)
            assertEquals(dirty, game.session.sramDirtySequence()) // copiar la pantalla no toca la partida
            // Una escena lisa (pantalla en blanco) no se fija y no cambia la elección.
            assertEquals(false, repo.pinCapture(game.fingerprint, frame))
            assertEquals(CoverChoice.AUTO, repo.choiceFor(game.fingerprint))
            val scene = frame.copyOf().also { for (i in 0 until 160) it[i] = 0xFF0000FF.toInt() }
            assertTrue(repo.pinCapture(game.fingerprint, scene))
            assertEquals(CoverChoice.CAPTURE, repo.choiceFor(game.fingerprint))
            val pinnedBytes = File(root, "artwork-pinned/${game.fingerprint}.png").readBytes()

            assertEquals(CloseResult.Closed, game.tryClose())
            assertTrue(File(root, "artwork/${game.fingerprint}.png").isFile) // el cierre guarda su captura…
            assertTrue(pinnedBytes.contentEquals(File(root, "artwork-pinned/${game.fingerprint}.png").readBytes())) // …sin pisar la fijada
            val entry = RomEntry("x.gb", "content://x", "x.gb", "X", RomConsole.GB, 0, true, null)
            assertEquals(CoverKind.CAPTURE, repo.resolve(entry, game.fingerprint))
            val shown = repo.load(entry, game.fingerprint)
            assertEquals(CoverKind.CAPTURE, shown.kind)
            assertEquals(160, shown.image!!.width)
        }
    }
}
