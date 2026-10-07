package com.joelbermudez.pocketgb.input

import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.GbaButtonBits
import com.joelbermudez.pocketgb.testing.SyntheticGbaRom
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** N8: L y R táctiles en la vista real (dedos y TalkBack) y su llegada a la sesión de GBA (bits 9 y 8). */
@RunWith(AndroidJUnit4::class)
class GbaControlsTest {
    private val masks = CopyOnWriteArrayList<Int>()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var view: GameControlsView
    private lateinit var provider: AccessibilityNodeProvider

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            view = GameControlsView(activity, onMaskChanged = { masks += it }).apply {
                hapticsEnabled = false
                shoulders = true
            }
            activity.setContentView(view)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain { provider = view.accessibilityNodeProvider!! }
    }

    @After
    fun tearDown() = scenario.close()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent = MotionEvent.obtain(0L, 10L, action, x, y, 0)

    private fun node(id: ControlId): AccessibilityNodeInfo = onMain { provider.createAccessibilityNodeInfo(id.ordinal)!! }

    @Test
    fun touchingLAndRPressesTheirBits() {
        val l = view.controlGeometry.frames.getValue(ControlId.L)
        onMain { view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, l.centerX, l.centerY)) }
        assertEquals(GbaButtonBits.L, masks.last())
        onMain { view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, l.centerX, l.centerY)) }
        assertEquals(0, masks.last())
        val r = view.controlGeometry.frames.getValue(ControlId.R)
        onMain { view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, r.centerX, r.centerY)) }
        assertEquals(GbaButtonBits.R, masks.last())
        onMain { view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, r.centerX, r.centerY)) }
        assertEquals(0, masks.last())
    }

    @Test
    fun talkBackHasNodesForLAndRThatPressThem() {
        val host = onMain { provider.createAccessibilityNodeInfo(View.NO_ID)!! }
        assertEquals("cruceta, A, B, Start, Select, L y R", 7, host.childCount)
        assertEquals("Botón L", node(ControlId.L).contentDescription.toString())
        assertEquals("Botón R", node(ControlId.R).contentDescription.toString())
        val bounds = android.graphics.Rect().also(node(ControlId.L)::getBoundsInParent)
        val density = view.resources.displayMetrics.density
        assertTrue(bounds.width() / density >= 47.5f && bounds.height() / density >= 47.5f)
        masks.clear()
        assertTrue(onMain { provider.performAction(ControlId.R.ordinal, AccessibilityNodeInfo.ACTION_CLICK, null) })
        assertTrue(masks.contains(GbaButtonBits.R))
        // En Game Boy no hay nodos de L/R.
        onMain { view.shoulders = false }
        Thread.sleep(250)
        assertEquals(5, onMain { provider.createAccessibilityNodeInfo(View.NO_ID)!! }.childCount)
        assertNull(onMain { provider.performAction(ControlId.L.ordinal, AccessibilityNodeInfo.ACTION_CLICK, null) }.takeIf { it })
    }

    @Test
    fun lAndRReachTheGbaSessionButNeverAGameBoyOne() {
        EmulatorSession(Console.GBA).use { session ->
            session.loadGba(SyntheticGbaRom.idle())
            session.setTouchButtons(GbaButtonBits.L or GbaButtonBits.R)
            assertEquals(GbaButtonBits.L or GbaButtonBits.R, session.requestedButtons)
        }
        EmulatorSession(Console.GB).use { session ->
            session.load(com.joelbermudez.pocketgb.testing.SyntheticRom.sramCounter())
            session.setTouchButtons(GbaButtonBits.L or GbaButtonBits.R)
            assertEquals("la máscara se recorta a 8 bits en GB", 0, session.requestedButtons)
        }
    }
}
