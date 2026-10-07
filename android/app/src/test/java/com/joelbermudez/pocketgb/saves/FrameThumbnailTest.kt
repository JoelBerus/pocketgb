package com.joelbermudez.pocketgb.saves

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameThumbnailTest {
    // El core entrega RGBA8888 con R en el byte bajo (0xAABBGGRR como Int little-endian);
    // Bitmap.setPixels espera ARGB (0xAARRGGBB): hay que intercambiar R y B.
    @Test fun swapsRedAndBlueKeepingGreenAndAlpha() {
        assertArrayEquals(intArrayOf(0xFF332211.toInt()), FrameThumbnail.swapRedBlue(intArrayOf(0xFF112233.toInt())))
        assertArrayEquals(intArrayOf(0x80AABBCC.toInt()), FrameThumbnail.swapRedBlue(intArrayOf(0x80CCBBAA.toInt())))
    }

    @Test fun pureRedBecomesPureRed() {
        // R puro en el core = 0xFF0000FF (byte bajo FF); en ARGB es 0xFFFF0000.
        assertEquals(0xFFFF0000.toInt(), FrameThumbnail.swapRedBlue(intArrayOf(0xFF0000FF.toInt()))[0])
        assertEquals(0xFF0000FF.toInt(), FrameThumbnail.swapRedBlue(intArrayOf(0xFFFF0000.toInt()))[0])
    }

    @Test fun isItsOwnInverseAndDoesNotMutateTheInput() {
        val src = IntArray(160 * 144) { it * 2654435761L.toInt() }
        val copy = src.copyOf()
        val once = FrameThumbnail.swapRedBlue(src)
        assertArrayEquals(copy, src)
        assertArrayEquals(src, FrameThumbnail.swapRedBlue(once))
        assertEquals(src.size, once.size)
    }

    @Test fun emptyInput() {
        assertEquals(0, FrameThumbnail.swapRedBlue(IntArray(0)).size)
    }
}
