package com.joelbermudez.pocketgb.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsRenderOptionsTest {
    @Test
    fun normalFillFollowsTheChosenOpacity() {
        val low = ControlsRenderOptions(opacity = 30)
        val full = ControlsRenderOptions(opacity = 100)
        assertEquals(0.72f * 0.3f, low.fillAlpha(pressed = false, fade = 1f), 0.001f)
        assertEquals(0.72f, full.fillAlpha(pressed = false, fade = 1f), 0.001f)
        assertEquals(0.92f, full.fillAlpha(pressed = true, fade = 1f), 0.001f)
    }

    @Test
    fun highContrastIsSolidWhateverTheOpacity() {
        for (opacity in listOf(30, 50, 70, 100)) {
            val options = ControlsRenderOptions(opacity = opacity, highContrast = true)
            assertTrue(options.fillAlpha(pressed = false, fade = 1f) >= 0.9f)
            assertTrue(options.fillAlpha(pressed = true, fade = 1f) >= 0.9f)
        }
    }

    @Test
    fun highContrastStillHonoursTheFade() {
        val options = ControlsRenderOptions(opacity = 30, highContrast = true)
        assertEquals(0f, options.fillAlpha(pressed = false, fade = 0f), 0f)
    }

    @Test
    fun highContrastLabelsAndRingAreFullyOpaqueAndTheRingIsThick() {
        val options = ControlsRenderOptions(opacity = 30, highContrast = true)
        assertEquals(1f, options.labelAlpha(fade = 1f), 0f)
        assertEquals(1f, options.ringAlpha(accent = false, fade = 1f), 0f)
        assertEquals(2f, ControlsRenderOptions.RING_WIDTH_DP, 0f)
        assertTrue(options.ringWidthDp >= 2f)
    }

    @Test
    fun normalRingIsTranslucentForNeutralAndNearlyOpaqueForAccents() {
        val options = ControlsRenderOptions(opacity = 100)
        assertEquals(0.5f, options.ringAlpha(accent = false, fade = 1f), 0.001f)
        assertEquals(0.95f, options.ringAlpha(accent = true, fade = 1f), 0.001f)
    }
}
