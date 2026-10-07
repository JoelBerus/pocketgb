package com.joelbermudez.pocketgb.video

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
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
