package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.ControlsVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsFadeControllerTest {
    private var now = 1_000L
    private fun controller(visibility: ControlsVisibility) = ControlsFadeController(visibility, { now })

    @Test
    fun alwaysIsOpaqueForever() {
        val fade = controller(ControlsVisibility.ALWAYS)
        now += 60_000
        assertEquals(1f, fade.alpha(), 0f)
        assertNull(fade.msUntilChange())
    }

    @Test
    fun onTouchFadesAfterThreeSecondsAndReappearsWhenTouched() {
        val fade = controller(ControlsVisibility.ON_TOUCH)
        now += 2_999
        assertEquals(1f, fade.alpha(), 0f)
        assertEquals(1L, fade.msUntilChange())
        now += 1 + ControlsFadeController.FADE_DURATION_MS / 2
        assertEquals(0.5f, fade.alpha(), 0.01f)
        now += ControlsFadeController.FADE_DURATION_MS
        assertEquals(0f, fade.alpha(), 0f)
        assertNull(fade.msUntilChange())

        fade.onTouch()
        assertEquals(1f, fade.alpha(), 0f)
        now += 3_000
        assertEquals(1f, fade.alpha(), 0f)
    }

    @Test
    fun hiddenNeverDrawsControlsButShowsTheHintForThreeSeconds() {
        val fade = controller(ControlsVisibility.HIDDEN)
        assertEquals(0f, fade.alpha(), 0f)
        assertTrue(fade.hintAlpha() > 0.99f)
        now += 2_999
        assertTrue(fade.hintAlpha() > 0.99f)
        now += 1 + ControlsFadeController.FADE_DURATION_MS
        assertEquals(0f, fade.hintAlpha(), 0f)
        assertEquals(0f, fade.alpha(), 0f)
        fade.onTouch() // tocar no los muestra: la pista solo sale al empezar o al cambiar el ajuste
        assertEquals(0f, fade.alpha(), 0f)
        assertEquals(0f, fade.hintAlpha(), 0f)
    }

    @Test
    fun changingVisibilityRestartsTheTimer() {
        val fade = controller(ControlsVisibility.ON_TOUCH)
        now += 10_000
        assertEquals(0f, fade.alpha(), 0f)
        fade.visibility = ControlsVisibility.HIDDEN
        assertTrue(fade.hintAlpha() > 0.99f)
        fade.visibility = ControlsVisibility.ON_TOUCH
        assertEquals(1f, fade.alpha(), 0f)
        assertFalse(fade.hintAlpha() > 0f)
    }

    @Test
    fun reduceMotionHidesAtOnceAfterTheDelayWithoutAFade() {
        val fade = controller(ControlsVisibility.ON_TOUCH).apply { reduceMotion = true }
        now += ControlsFadeController.DELAY_MS
        assertEquals(1f, fade.alpha(), 0f)
        now += 1
        assertEquals(0f, fade.alpha(), 0f)
        assertNull(fade.msUntilChange())
    }

    @Test
    fun reduceMotionNeverReportsIntermediateAlpha() {
        val fade = controller(ControlsVisibility.ON_TOUCH).apply { reduceMotion = true }
        for (elapsed in 0L..4_000L step 50L) {
            now = 1_000L + elapsed
            val alpha = fade.alpha()
            assertTrue("alfa intermedio $alpha a los $elapsed ms", alpha == 0f || alpha == 1f)
        }
    }

    @Test
    fun reduceMotionStillSchedulesTheHideAndTheHintEndsWithoutRamp() {
        val fade = controller(ControlsVisibility.ON_TOUCH).apply { reduceMotion = true }
        now += 1_000
        assertEquals(2_000L, fade.msUntilChange())
        val hidden = controller(ControlsVisibility.HIDDEN).apply { reduceMotion = true }
        now += ControlsFadeController.DELAY_MS + 1
        assertEquals(0f, hidden.hintAlpha(), 0f)
    }
}
