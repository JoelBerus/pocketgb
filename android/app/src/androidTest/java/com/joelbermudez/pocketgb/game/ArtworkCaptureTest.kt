package com.joelbermudez.pocketgb.game

import android.graphics.BitmapFactory
import com.joelbermudez.pocketgb.emulator.CoreBridge
import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.saves.CloseResult
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.openGame
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Portada al cerrar (A6-L3, K9): el gancho recibe el último fotograma y nunca altera el cierre. */
class ArtworkCaptureTest {
    private lateinit var root: File

    @Before fun setUp() { root = tempDir("artwork-capture") }

    @After fun tearDown() { root.deleteRecursively() }

    private fun playAndPause(game: GameSession) {
        game.start()
        val before = game.session.sramDirtySequence()
        assertTrue(waitUntil { game.session.sramDirtySequence() > before + 2 })
        game.pause()
    }

    @Test
    fun closingAParkedSessionDeliversTheLastFrameAndKeepsTheCloseResult() {
        openGame(SyntheticRom.sramCounter()).use { g ->
            var frame: IntArray? = null
            g.game.parkedFrameCallback = { frame = it }
            playAndPause(g.game)
            assertEquals(CloseResult.Closed, g.game.tryClose())
            assertEquals(CoreBridge.FRAME_PIXELS, frame!!.size)
            assertEquals(SessionState.Closed, g.session.state.value)
        }
    }

    @Test
    fun aFailingHookNeverChangesTheCloseResultNorLeavesTheSessionOpen() {
        openGame(SyntheticRom.sramCounter()).use { g ->
            g.game.parkedFrameCallback = { error("el gancho falla") }
            playAndPause(g.game)
            assertEquals(CloseResult.Closed, g.game.tryClose())
            assertTrue(g.game.isClosed)
            assertEquals(SessionState.Closed, g.session.state.value)
            assertFalse(g.game.holdsLease)
        }
    }

    @Test
    fun aSessionThatIsNotParkedDoesNotCapture() {
        openGame(SyntheticRom.sramCounter()).use { g ->
            var calls = 0
            g.game.parkedFrameCallback = { calls++ }
            // Nunca se arrancó: estado Ready, no Paused.
            assertEquals(CloseResult.Closed, g.game.tryClose())
            assertEquals(0, calls)
        }
    }

    private fun newViewModel() = GameplayViewModel(
        GameplayTestHost.launcher(root),
        rescue = { it() },
        rescueAttempts = 1,
        rescueRetryDelayMs = 10,
    )

    private fun openAndPlay(vm: GameplayViewModel): GameSession {
        vm.open(GameplayTestHost.entry)
        assertTrue(waitUntil(15_000) { vm.game.value != null })
        val game = vm.game.value!!
        assertTrue(waitUntil { game.session.sramDirtySequence() > 3 })
        return game
    }

    @Test
    fun exitingThroughTheViewModelWritesTheCoverPng() {
        val store = ArtworkStore(File(root, "artwork"), executor = null)
        val vm = newViewModel()
        var fingerprint: String? = null
        vm.setCoverSink { fp, pixels ->
            fingerprint = fp
            // La ROM sintética deja la pantalla en blanco (uniforme y por tanto descartable): se fuerza un píxel distinto
            // para comprobar la ruta completa hasta el PNG.
            store.save(fp, pixels.copyOf().also { it[0] = it[0] xor 0x00FFFFFF })
        }
        val game = openAndPlay(vm)
        vm.exit()
        assertTrue(waitUntil(15_000) { vm.game.value == null })
        assertTrue(game.isClosed)
        val png = File(root, "artwork/${fingerprint!!}.png")
        assertTrue("debe existir la portada", png.isFile)
        val bitmap = BitmapFactory.decodeFile(png.path)
        assertNotNull(bitmap)
        assertEquals(CoreBridge.SCREEN_WIDTH, bitmap.width)
        assertEquals(CoreBridge.SCREEN_HEIGHT, bitmap.height)
    }

    @Test
    fun aCoverSinkThatThrowsDoesNotBreakExit() {
        val vm = newViewModel()
        vm.setCoverSink { _, _ -> error("sin espacio") }
        val game = openAndPlay(vm)
        vm.exit()
        assertTrue(waitUntil(15_000) { vm.game.value == null })
        assertTrue(game.isClosed)
        assertNull(vm.dialog.value)
    }

    @Test
    fun withoutASinkNothingIsWritten() {
        val vm = newViewModel()
        val game = openAndPlay(vm)
        vm.exit()
        assertTrue(waitUntil(15_000) { vm.game.value == null })
        assertTrue(game.isClosed)
        assertFalse(File(root, "artwork").exists())
    }
}
