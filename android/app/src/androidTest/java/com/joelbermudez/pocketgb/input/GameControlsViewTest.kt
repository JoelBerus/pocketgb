package com.joelbermudez.pocketgb.input

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
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

    private fun event(action: Int, x: Float, y: Float): MotionEvent = MotionEvent.obtain(
        0L,
        10L,
        action,
        x,
        y,
        0,
    )
}
