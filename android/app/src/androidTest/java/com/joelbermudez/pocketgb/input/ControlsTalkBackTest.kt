package com.joelbermudez.pocketgb.input

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.R
import java.util.concurrent.CopyOnWriteArrayList
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.DpadStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** TalkBack en los controles táctiles (A7-L2, R7): nodos virtuales reales, sin servicio de accesibilidad. */
@RunWith(AndroidJUnit4::class)
class ControlsTalkBackTest {
    private val masks = CopyOnWriteArrayList<Int>()
    @Volatile private var menuOpened = 0
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var view: GameControlsView
    private lateinit var provider: AccessibilityNodeProvider
    private var density = 1f

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            density = activity.resources.displayMetrics.density
            view = GameControlsView(activity, onMaskChanged = { masks += it }, onMenu = { menuOpened++ }).apply {
                hapticsEnabled = false
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

    private fun node(id: ControlId): AccessibilityNodeInfo = onMain { provider.createAccessibilityNodeInfo(id.ordinal)!! }

    private fun settle() = Thread.sleep(250)

    @Test
    fun hiddenControlsAnnounceTheirHiddenStateToTalkBack() {
        assertNull("visibles: sin estado", node(ControlId.A).stateDescription)
        onMain { view.controlsVisibility = ControlsVisibility.HIDDEN }
        settle()
        ControlId.entries.filter { it != ControlId.MENU && !it.isShoulder }.forEach {
            assertEquals("$it oculto", view.context.getString(R.string.controls_a11y_hidden), node(it).stateDescription)
        }
        onMain { view.controlsVisibility = ControlsVisibility.ALWAYS }
        settle()
        assertNull(node(ControlId.A).stateDescription)
    }

    @Test
    fun hostExposesOneChildPerVisibleControl() {
        val host = onMain { provider.createAccessibilityNodeInfo(View.NO_ID)!! }
        assertEquals(5, host.childCount)
        assertNull("la vista ya no lleva una descripción única", view.contentDescription)
    }

    @Test
    fun everyVirtualNodeHasItsLabelAndAtLeast48Dp() {
        val expected = mapOf(
            ControlId.DPAD to "Cruceta",
            ControlId.A to "Botón A",
            ControlId.B to "Botón B",
            ControlId.START to "Start",
            ControlId.SELECT to "Select",
        )
        expected.forEach { (id, label) ->
            val info = node(id)
            assertEquals(label, info.contentDescription.toString())
            val bounds = android.graphics.Rect().also(info::getBoundsInParent)
            assertTrue("$id ancho ${bounds.width() / density} dp", bounds.width() / density >= 47.5f)
            assertTrue("$id alto ${bounds.height() / density} dp", bounds.height() / density >= 47.5f)
        }
    }

    @Test
    fun theDpadNodeFollowsSeparatedArrowsAtEverySeparationAndKeepsItsActions() {
        for (separation in listOf(0.7f, 1f, 1.5f)) {
            onMain {
                view.renderOptions = view.renderOptions.copy(dpadStyle = DpadStyle.ARROWS)
                view.controlLayout = ControlLayout.defaults(ControlsOrientation.PORTRAIT).copy(separation = separation)
            }
            settle()
            val info = node(ControlId.DPAD)
            assertEquals("Cruceta", info.contentDescription.toString())
            val bounds = android.graphics.Rect().also(info::getBoundsInParent)
            assertTrue("separación $separation: ancho ${bounds.width() / density} dp", bounds.width() / density >= 47.5f)
            assertTrue("separación $separation: alto ${bounds.height() / density} dp", bounds.height() / density >= 47.5f)
            // El nodo envuelve las cuatro flechas separadas.
            val geometry = view.controlGeometry
            DpadShape.arrowCircles(geometry.frames.getValue(ControlId.DPAD), geometry.dpadSeparation).values.forEach { c ->
                assertTrue(c.centerX - c.radius >= bounds.left - 1f && c.centerX + c.radius <= bounds.right + 1f)
                assertTrue(c.centerY - c.radius >= bounds.top - 1f && c.centerY + c.radius <= bounds.bottom + 1f)
            }
            // Siguen ofreciéndose las cuatro acciones de dirección y funcionan.
            masks.clear()
            assertTrue(onMain { provider.performAction(ControlId.DPAD.ordinal, R.id.a11y_action_up, null) })
            assertTrue(masks.contains(GameBoyButton.UP.mask))
            settle()
        }
    }

    @Test
    fun theMenuNodeAppearsOnlyWhenTheMenuControlIsShown() {
        onMain { view.renderOptions = view.renderOptions.copy(showMenu = true) }
        val host = onMain { provider.createAccessibilityNodeInfo(View.NO_ID)!! }
        assertEquals(6, host.childCount)
        assertEquals("Menú", node(ControlId.MENU).contentDescription.toString())
    }

    @Test
    fun hitTestingFindsTheVirtualNodeUnderTheFinger() {
        val a = view.controlGeometry.frames.getValue(ControlId.A)
        val dpad = view.controlGeometry.frames.getValue(ControlId.DPAD)
        val helper = ViewHelperAccess.helperOf(view)
        assertEquals(ControlId.A.ordinal, helper.virtualViewAt(a.centerX, a.centerY))
        assertEquals(ControlId.DPAD.ordinal, helper.virtualViewAt(dpad.centerX, dpad.centerY))
        assertEquals(androidx.customview.widget.ExploreByTouchHelper.INVALID_ID, helper.virtualViewAt(1f, 1f))
    }

    @Test
    fun clickOnAPressesTheABitForAbout100Ms() {
        assertTrue(onMain { provider.performAction(ControlId.A.ordinal, AccessibilityNodeInfo.ACTION_CLICK, null) })
        assertEquals(GameBoyButton.A.mask, masks.last())
        settle()
        assertEquals(0, masks.last())
        assertEquals(listOf(GameBoyButton.A.mask, 0), masks.toList())
    }

    @Test
    fun clicksOnEachButtonPressTheirOwnBit() {
        mapOf(
            ControlId.B to GameBoyButton.B.mask,
            ControlId.START to GameBoyButton.START.mask,
            ControlId.SELECT to GameBoyButton.SELECT.mask,
        ).forEach { (id, mask) ->
            masks.clear()
            assertTrue(onMain { provider.performAction(id.ordinal, AccessibilityNodeInfo.ACTION_CLICK, null) })
            assertEquals(mask, masks.first())
            settle()
            assertEquals(0, masks.last())
        }
    }

    @Test
    fun theDpadOffersFourDirectionActionsThatPressForAbout100Ms() {
        val labels = node(ControlId.DPAD).actionList.mapNotNull { it.label?.toString() }
        assertTrue(labels.containsAll(listOf("Arriba", "Abajo", "Izquierda", "Derecha")))
        mapOf(
            R.id.a11y_action_up to GameBoyButton.UP.mask,
            R.id.a11y_action_down to GameBoyButton.DOWN.mask,
            R.id.a11y_action_left to GameBoyButton.LEFT.mask,
            R.id.a11y_action_right to GameBoyButton.RIGHT.mask,
        ).forEach { (action, mask) ->
            masks.clear()
            assertTrue(onMain { provider.performAction(ControlId.DPAD.ordinal, action, null) })
            assertEquals(mask, masks.first())
            settle()
            assertEquals(0, masks.last())
        }
    }

    @Test
    fun aDirectionActionIsRejectedOnAButtonNode() {
        masks.clear()
        assertFalse(onMain { provider.performAction(ControlId.A.ordinal, R.id.a11y_action_up, null) })
        assertTrue(masks.isEmpty())
    }

    @Test
    fun theRootOffersOpenMenuAndInvokesTheMenu() {
        val host = onMain { provider.createAccessibilityNodeInfo(View.NO_ID)!! }
        assertTrue(host.actionList.any { it.label?.toString() == "Abrir menú" })
        assertTrue(onMain { provider.performAction(View.NO_ID, R.id.a11y_action_open_menu, null) })
        assertEquals(1, menuOpened)
    }

    @Test
    fun theMenuNodeOffersOpenMenuToo() {
        onMain { view.renderOptions = view.renderOptions.copy(showMenu = true) }
        assertTrue(node(ControlId.MENU).actionList.any { it.label?.toString() == "Abrir menú" })
        assertTrue(onMain { provider.performAction(ControlId.MENU.ordinal, R.id.a11y_action_open_menu, null) })
        assertEquals(1, menuOpened)
    }

    @Test
    fun anAccessibilityPressIsCombinedWithAHeldTouch() {
        val b = view.controlGeometry.frames.getValue(ControlId.B)
        onMain {
            view.dispatchTouchEvent(motion(android.view.MotionEvent.ACTION_DOWN, b.centerX, b.centerY))
        }
        assertEquals(GameBoyButton.B.mask, masks.last())
        assertTrue(onMain { provider.performAction(ControlId.START.ordinal, AccessibilityNodeInfo.ACTION_CLICK, null) })
        assertEquals(GameBoyButton.B.mask or GameBoyButton.START.mask, masks.last())
        settle()
        assertEquals("el dedo sigue en B", GameBoyButton.B.mask, masks.last())
    }

    @Test
    fun releaseCancelsPendingAccessibilityPresses() {
        assertTrue(onMain { provider.performAction(ControlId.A.ordinal, AccessibilityNodeInfo.ACTION_CLICK, null) })
        onMain { view.release() }
        assertEquals(0, masks.last())
        settle()
        assertEquals(0, masks.last())
    }

    @Test
    fun editingModeExposesNoGameActions() {
        onMain { view.editing = true }
        assertFalse(onMain { provider.performAction(ControlId.A.ordinal, AccessibilityNodeInfo.ACTION_CLICK, null) })
        assertFalse(onMain { provider.performAction(View.NO_ID, R.id.a11y_action_open_menu, null) })
        assertEquals(0, menuOpened)
    }

    private fun motion(action: Int, x: Float, y: Float) =
        android.view.MotionEvent.obtain(0L, 0L, action, x, y, 0)
}

/** Acceso de pruebas al helper instalado en la vista. */
internal object ViewHelperAccess {
    fun helperOf(view: GameControlsView): HelperProbe = HelperProbe(view)

    class HelperProbe(private val view: GameControlsView) {
        fun virtualViewAt(x: Float, y: Float): Int {
            val helper = androidx.core.view.ViewCompat.getAccessibilityDelegate(view) as ControlsAccessibilityHelper
            return helper.probeVirtualViewAt(x, y)
        }
    }
}
