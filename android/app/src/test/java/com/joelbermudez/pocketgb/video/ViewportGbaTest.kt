package com.joelbermudez.pocketgb.video

import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** N8: `Viewport` con la misma geometría que el blit nativo (`compute_layout`) también a 240×160 (3:2). */
class ViewportGbaTest {
    private val gba = Console.GBA.screen

    /** `compute_layout` de `native_session.c`, transcrito para comparar. */
    private fun native(width: Int, height: Int, mode: ScaleMode, sw: Int, sh: Int): Viewport {
        val k = minOf(width / sw, height / sh)
        var dw: Int
        var dh: Int
        if (mode == ScaleMode.INTEGER && k >= 1) { dw = sw * k; dh = sh * k } else if (width.toLong() * sh <= height.toLong() * sw) {
            dw = width; dh = (width.toLong() * sh / sw).toInt()
        } else {
            dh = height; dw = (height.toLong() * sw / sh).toInt()
        }
        if (dw < 1) dw = 1
        if (dh < 1) dh = 1
        return Viewport((width - dw) / 2, (height - dh) / 2, dw, dh, if (mode == ScaleMode.INTEGER) k else 0)
    }

    @Test
    fun portraitFillIsThreeToTwoAtFullWidth() {
        val v = Viewport.calculate(1080, 720, ScaleMode.FILL, gba)
        assertEquals(Viewport(0, 0, 1080, 720, 0), v)
        assertEquals("GB sigue en 10:9", Viewport(0, 0, 1080, 972, 0), Viewport.calculate(1080, 972, ScaleMode.FILL))
    }

    @Test
    fun landscapeIntegerUsesTheLargestMultipleWithBands() {
        // Teléfono 2400×1080: ×6 = 1440×960 en GBA (×7 = 1120×1008 en GB).
        assertEquals(Viewport(480, 60, 1440, 960, 6), Viewport.calculate(2400, 1080, ScaleMode.INTEGER, gba))
        assertEquals(Viewport(640, 36, 1120, 1008, 7), Viewport.calculate(2400, 1080, ScaleMode.INTEGER))
    }

    @Test
    fun matchesTheNativeLayoutForEverySizeAndMode() {
        for (w in listOf(1, 100, 239, 240, 241, 480, 719, 1080, 1440, 2400, 3200)) {
            for (h in listOf(1, 100, 159, 160, 161, 320, 720, 1080, 1600, 2400)) {
                for (mode in ScaleMode.entries) {
                    assertEquals("$w×$h $mode", native(w, h, mode, 240, 160), Viewport.calculate(w, h, mode, gba))
                    assertEquals("$w×$h $mode GB", native(w, h, mode, 160, 144), Viewport.calculate(w, h, mode))
                }
            }
        }
    }
}
