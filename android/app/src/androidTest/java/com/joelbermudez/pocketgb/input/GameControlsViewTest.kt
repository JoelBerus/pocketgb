package com.joelbermudez.pocketgb.input

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.DpadStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameControlsViewTest {
    @Test
    fun slideHapticsAndCancelPublishExpectedMasks() {
        val masks = mutableListOf<Int>()
        var haptics = 0
        val view = GameControlsView(
            context = ApplicationProvider.getApplicationContext(),
            onMaskChanged = masks::add,
        ).apply {
            hapticFeedback = { haptics += 1 }
            layout(0, 0, 400, 700)
        }
        val b = view.controlGeometry.frames.getValue(ControlId.B)
        val a = view.controlGeometry.frames.getValue(ControlId.A)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, b.centerX, b.centerY))
        assertEquals(GameBoyButton.B.mask, masks.last())
        assertEquals(1, haptics)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, b.centerX, b.centerY))
        assertEquals(1, haptics)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, a.centerX, a.centerY))
        assertEquals(GameBoyButton.A.mask, masks.last())
        assertEquals(2, haptics)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, a.centerX, a.centerY))
        assertEquals(0, masks.last())
    }

    @Test
    fun releasingViewAlwaysClearsInput() {
        val masks = mutableListOf<Int>()
        val view = GameControlsView(
            context = ApplicationProvider.getApplicationContext(),
            onMaskChanged = masks::add,
        ).apply { layout(0, 0, 400, 700) }
        val a = view.controlGeometry.frames.getValue(ControlId.A)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, a.centerX, a.centerY))

        view.release()

        assertEquals(0, masks.last())
    }

    private fun view(masks: MutableList<Int> = mutableListOf()) = GameControlsView(
        context = ApplicationProvider.getApplicationContext(),
        onMaskChanged = masks::add,
    ).apply { layout(0, 0, 400, 700) }

    @Test
    fun opacityIsVisualOnlyAndNeverChangesTheTouchArea() {
        val results = mutableListOf<Pair<Int, Int>>()
        listOf(30, 100).forEach { opacity ->
            val masks = mutableListOf<Int>()
            val view = view(masks)
            view.renderOptions = ControlsRenderOptions(opacity = opacity)
            val b = view.controlGeometry.frames.getValue(ControlId.B)
            // Borde del área táctil (más allá del dibujo): se responde igual con cualquier opacidad.
            val touch = view.controlGeometry.touchFrame(ControlId.B)
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, b.centerX, b.centerY))
            results += opacity to masks.last()
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, b.centerX, b.centerY))
            assertEquals(touch, view.controlGeometry.touchFrame(ControlId.B))
        }
        assertEquals(GameBoyButton.B.mask, results[0].second)
        assertEquals(results[0].second, results[1].second)
    }

    @Test
    fun hiddenControlsStillRespondToTouches() {
        val masks = mutableListOf<Int>()
        val view = view(masks)
        view.controlsVisibility = ControlsVisibility.HIDDEN
        val a = view.controlGeometry.frames.getValue(ControlId.A)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, a.centerX, a.centerY))
        assertEquals(GameBoyButton.A.mask, masks.last())
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, a.centerX, a.centerY))
        assertEquals(0, masks.last())
    }

    @Test
    fun dpadGivesASectorTickPerSectorChangeAndFaceButtonsAnImpact() {
        var impacts = 0
        var ticks = 0
        val view = view().apply {
            hapticFeedback = { impacts += 1 }
            sectorFeedback = { ticks += 1 }
        }
        val dpad = view.controlGeometry.frames.getValue(ControlId.DPAD)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, dpad.right, dpad.centerY))
        assertEquals("sector derecha", 1, ticks)
        assertEquals("la cruceta no da impacto", 0, impacts)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, dpad.right, dpad.centerY))
        assertEquals("mismo sector: sin háptica", 1, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, dpad.centerX, dpad.top))
        assertEquals("sector arriba", 2, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, dpad.centerX, dpad.centerY))
        assertEquals("zona muerta: sin háptica", 2, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, dpad.centerX, dpad.centerY))

        val a = view.controlGeometry.frames.getValue(ControlId.A)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, a.centerX, a.centerY))
        assertEquals(1, impacts)
        assertEquals(2, ticks)
    }

    @Test
    fun hapticsOffGivesNoFeedback() {
        var count = 0
        val view = view().apply {
            hapticsEnabled = false
            hapticFeedback = { count += 1 }
            sectorFeedback = { count += 1 }
        }
        val a = view.controlGeometry.frames.getValue(ControlId.A)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, a.centerX, a.centerY))
        assertEquals(0, count)
    }

    @Test
    fun theMenuControlIsNotDrawnNorTouchableByDefault() {
        var menus = 0
        val view = view().apply { onMenu = { menus += 1 } }
        val menu = view.controlGeometry.frames.getValue(ControlId.MENU)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, menu.centerX, menu.centerY))
        assertEquals(0, menus)
        assertEquals(false, view.renderOptions.showMenu)
    }

    @Test
    fun arrowsKeepTheSameSectorsAsTheCross() {
        listOf(DpadStyle.CROSS, DpadStyle.ARROWS).forEach { style ->
            val masks = mutableListOf<Int>()
            val view = view(masks)
            view.renderOptions = ControlsRenderOptions(dpadStyle = style)
            val dpad = view.controlGeometry.frames.getValue(ControlId.DPAD)
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, dpad.centerX, dpad.top))
            assertEquals("$style", GameBoyButton.UP.mask, masks.last())
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, dpad.centerX, dpad.top))
        }
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent = MotionEvent.obtain(
        0L,
        10L,
        action,
        x,
        y,
        0,
    )
}
