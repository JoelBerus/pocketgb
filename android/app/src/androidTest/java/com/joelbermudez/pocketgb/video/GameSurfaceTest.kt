package com.joelbermudez.pocketgb.video

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.joelbermudez.pocketgb.MainActivity
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.ScaleMode
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSurfaceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun surfaceCanBeRecreatedWhileSessionKeepsRunning() {
        val visible = mutableStateOf(true)
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    PocketGBTheme {
                        if (visible.value) {
                            GameSurface(
                                session = session,
                                modifier = Modifier.fillMaxSize().testTag("game-surface"),
                            )
                        }
                    }
                }
            }
            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            val before = session.frameCount

            repeat(25) {
                compose.runOnUiThread { visible.value = false }
                compose.waitForIdle()
                compose.runOnUiThread { visible.value = true }
                compose.waitForIdle()
            }

            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            check(session.frameCount > before)
        }
    }

    @Test
    fun fillModeIsAppliedToTheSessionAndSurvivesSurfaceRecreation() {
        val visible = mutableStateOf(true)
        val mode = mutableStateOf(ScaleMode.FILL)
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    PocketGBTheme {
                        if (visible.value) {
                            GameSurface(
                                session = session,
                                modifier = Modifier.fillMaxSize().testTag("game-surface"),
                                scaleMode = mode.value,
                            )
                        }
                    }
                }
            }
            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            compose.waitUntil(5_000) { session.scaleMode == ScaleMode.FILL }
            val before = session.frameCount

            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { visible.value = true }
            compose.waitForIdle()
            compose.runOnUiThread { mode.value = ScaleMode.INTEGER }
            compose.waitUntil(5_000) { session.scaleMode == ScaleMode.INTEGER }
            compose.runOnUiThread { mode.value = ScaleMode.FILL }
            compose.waitUntil(5_000) { session.scaleMode == ScaleMode.FILL }

            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            compose.waitUntil(5_000) { session.frameCount > before }
        }
    }

    /** A7 R12: un fabricante que destruye y recrea la superficie al rotar no produce doble adjunto ni deja la sesión huérfana. */
    @Test
    fun destroyAndRecreateOnTheSameViewAttachesOnceAtATime() {
        val attached = java.util.concurrent.atomic.AtomicInteger()
        val maxConcurrent = java.util.concurrent.atomic.AtomicInteger()
        val attaches = java.util.concurrent.atomic.AtomicInteger()
        val scales = java.util.concurrent.atomic.AtomicInteger()
        EmulatorSession().use { session ->
            session.load(SyntheticRom.romOnly())
            session.start()
            val sink = object : SurfaceSink {
                override fun attach(surface: android.view.Surface) {
                    maxConcurrent.set(maxOf(maxConcurrent.get(), attached.incrementAndGet()))
                    attaches.incrementAndGet()
                    session.attachSurface(surface)
                }
                override fun detach() {
                    attached.decrementAndGet()
                    session.detachSurface()
                }
                override fun applyScale(mode: ScaleMode) {
                    scales.incrementAndGet()
                    session.setScaleMode(mode)
                }
            }
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    PocketGBTheme {
                        GameSurface(sink, Modifier.fillMaxSize().testTag("game-surface"), ScaleMode.FILL)
                    }
                }
            }
            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            compose.waitUntil(5_000) { attaches.get() == 1 }
            val surfaceView = findSurfaceView(compose.activity.window.decorView)
            val scalesBefore = scales.get()

            repeat(5) {
                compose.runOnUiThread { surfaceView.visibility = android.view.View.GONE }
                compose.waitForIdle()
                compose.waitUntil(5_000) { attached.get() == 0 }
                compose.runOnUiThread { surfaceView.visibility = android.view.View.VISIBLE }
                compose.waitUntil(5_000) { attached.get() == 1 }
            }

            assertEquals("nunca dos adjuntos a la vez", 1, maxConcurrent.get())
            assertEquals("un adjunto por cada creación", 6, attaches.get())
            assertEquals(1, attached.get())
            assertTrue("surfaceChanged reaplica el escalado", scales.get() > scalesBefore)
            val before = session.frameCount
            compose.waitUntil(5_000) { session.frameCount > before }
        }
    }

    /** N8: una sesión de GBA (240×160) dibuja en la misma superficie, en los dos modos de escalado y al recrearla. */
    @Test
    fun gbaSessionDrawsAndSurvivesSurfaceRecreationInBothScaleModes() {
        val visible = mutableStateOf(true)
        val mode = mutableStateOf(ScaleMode.INTEGER)
        EmulatorSession(com.joelbermudez.pocketgb.emulator.Console.GBA).use { session ->
            session.loadGba(com.joelbermudez.pocketgb.testing.SyntheticGbaRom.sramCounter())
            session.start()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    PocketGBTheme {
                        if (visible.value) {
                            GameSurface(
                                session = session,
                                modifier = Modifier.fillMaxSize().testTag("game-surface"),
                                scaleMode = mode.value,
                            )
                        }
                    }
                }
            }
            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            repeat(10) {
                compose.runOnUiThread {
                    visible.value = !visible.value
                    if (visible.value) mode.value = if (mode.value == ScaleMode.FILL) ScaleMode.INTEGER else ScaleMode.FILL
                }
                compose.waitForIdle()
            }
            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            val before = session.frameCount
            compose.waitUntil(5_000) { session.frameCount > before + 10 }
            session.pause()
            assertEquals(240 * 160, session.copyFrame().size)
        }
    }

    /**
     * N8-H3: lo que llega a la superficie en GBA. Una ROM en forced blank (pantalla blanca) en una superficie de 700×400 px:
     * el rectángulo blanco es exactamente el que calcula el blit (3:2, centrado; entero ×2 = 480×320 o llenar = 600×400) y
     * todo lo demás son bandas negras. Se lee con `PixelCopy` de la `SurfaceView`.
     */
    @Test
    fun gbaImageKeepsThreeToTwoWithBlackBandsInIntegerAndFill() {
        val mode = mutableStateOf(ScaleMode.INTEGER)
        EmulatorSession(com.joelbermudez.pocketgb.emulator.Console.GBA).use { session ->
            session.loadGba(com.joelbermudez.pocketgb.testing.SyntheticGbaRom.forcedBlank())
            session.start()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    PocketGBTheme {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val width = with(density) { 700.toDp() }
                        val height = with(density) { 400.toDp() }
                        GameSurface(
                            session = session,
                            modifier = Modifier.size(width, height).testTag("game-surface"),
                            scaleMode = mode.value,
                        )
                    }
                }
            }
            compose.onNodeWithTag("game-surface").assertIsDisplayed()
            val view = findSurfaceView(compose.activity.window.decorView)
            for (scale in listOf(ScaleMode.INTEGER, ScaleMode.FILL, ScaleMode.INTEGER)) {
                compose.runOnUiThread { mode.value = scale }
                compose.waitUntil(5_000) { session.scaleMode == scale }
                val frames = session.frameCount
                compose.waitUntil(5_000) { session.frameCount > frames + 5 }
                assertGbaRect(capture(view), scale)
            }
        }
    }

    /** Comprueba píxel a píxel el rectángulo de dibujo con la misma geometría que `compute_layout` (native_session.c). */
    private fun assertGbaRect(bitmap: android.graphics.Bitmap, scale: ScaleMode) {
        val w = bitmap.width
        val h = bitmap.height
        val integer = minOf(w / 240, h / 160)
        val (drawW, drawH) = when {
            scale == ScaleMode.INTEGER && integer >= 1 -> 240 * integer to 160 * integer
            w.toLong() * 160 <= h.toLong() * 240 -> w to (w.toLong() * 160 / 240).toInt()
            else -> (h.toLong() * 240 / 160).toInt() to h
        }
        val left = (w - drawW) / 2
        val top = (h - drawH) / 2
        assertTrue("superficie de ${w}x$h: el rectángulo ${drawW}x$drawH debe ser 3:2", drawW * 2 == drawH * 3)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var wrong = 0
        var firstWrong = ""
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val inside = x in left until left + drawW && y in top until top + drawH
                val ok = if (inside) r > 0xF0 && g > 0xF0 && b > 0xF0 else r < 0x10 && g < 0x10 && b < 0x10
                if (!ok) {
                    if (wrong == 0) firstWrong = "(%d,%d)=#%08x".format(x, y, c)
                    wrong++
                }
            }
        }
        assertEquals("$scale en ${w}x$h: rect ${drawW}x$drawH en ($left,$top); primer píxel distinto $firstWrong", 0, wrong)
    }

    private fun capture(view: android.view.SurfaceView): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        val done = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicInteger(-1)
        android.view.PixelCopy.request(
            view, bitmap, { code -> result.set(code); done.countDown() },
            android.os.Handler(android.os.Looper.getMainLooper()),
        )
        assertTrue("PixelCopy no respondió", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("PixelCopy", android.view.PixelCopy.SUCCESS, result.get())
        return bitmap
    }

    private fun findSurfaceView(root: android.view.View): android.view.SurfaceView {
        if (root is android.view.SurfaceView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                runCatching { return findSurfaceView(root.getChildAt(i)) }
            }
        }
        error("sin SurfaceView")
    }
}
