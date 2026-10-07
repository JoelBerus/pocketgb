package com.joelbermudez.pocketgb.ui.details

import com.joelbermudez.pocketgb.library.RomEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N3a · detalle adaptable: dos columnas si el ancho supera al alto o mide ≥ 600 dp; en vertical, imagen ≤ 45 % del alto. */
class DetailLayoutTest {
    private val gb = 10f / 9f
    private val gba = 3f / 2f
    private val tolerance = 0.01f

    @Test
    fun portraitPhoneIsOneColumnWithTheImageAtMostFortyFivePercentHigh() {
        val layout = detailLayoutFor(widthDp = 360f, heightDp = 472f, aspectRatio = gb)
        assertFalse(layout.twoColumns)
        assertTrue("≤ 45 %: ${layout.artworkHeightDp}", layout.artworkHeightDp <= 472f * 0.45f + tolerance)
        assertEquals(gb, layout.artworkWidthDp / layout.artworkHeightDp, tolerance)
        assertTrue(layout.artworkWidthDp <= 360f - 2 * DETAIL_MARGIN_DP)
    }

    @Test
    fun aTallPortraitPhoneUsesTheFullWidth() {
        // Muy alto: la imagen ya cabe a todo el ancho por debajo del 45 %.
        val layout = detailLayoutFor(widthDp = 360f, heightDp = 1200f, aspectRatio = gb)
        assertFalse(layout.twoColumns)
        assertEquals(360f - 2 * DETAIL_MARGIN_DP, layout.artworkWidthDp, tolerance)
    }

    @Test
    fun landscapePhoneIsTwoColumnsWithTheImageLimitedInHeight() {
        // 640 × 360 menos el rail, la barra de estado y la barra superior.
        val layout = detailLayoutFor(widthDp = 560f, heightDp = 272f, aspectRatio = gb)
        assertTrue(layout.twoColumns)
        assertTrue("cabe en alto: ${layout.artworkHeightDp}", layout.artworkHeightDp <= 272f - DETAIL_VERTICAL_PADDING_DP + tolerance)
        assertTrue("deja sitio a la información", layout.artworkWidthDp <= (560f - 2 * DETAIL_MARGIN_DP - DETAIL_COLUMN_SPACING_DP) / 2 + tolerance)
        assertEquals(gb, layout.artworkWidthDp / layout.artworkHeightDp, tolerance)
    }

    @Test
    fun wideWindowsAreTwoColumnsEvenWhenTaller() {
        // Plegable desplegado o tablet en vertical: ≥ 600 dp de ancho.
        val layout = detailLayoutFor(widthDp = 700f, heightDp = 900f, aspectRatio = gb)
        assertTrue(layout.twoColumns)
        assertTrue(layout.artworkWidthDp <= (700f - 2 * DETAIL_MARGIN_DP - DETAIL_COLUMN_SPACING_DP) / 2 + tolerance)
    }

    @Test
    fun justBelowSixHundredInPortraitStaysOneColumn() {
        assertFalse(detailLayoutFor(widthDp = 599f, heightDp = 900f, aspectRatio = gb).twoColumns)
        assertTrue(detailLayoutFor(widthDp = 600f, heightDp = 900f, aspectRatio = gb).twoColumns)
        assertTrue(detailLayoutFor(widthDp = 500f, heightDp = 499f, aspectRatio = gb).twoColumns)
    }

    @Test
    fun theAspectRatioIsAParameterOfTheConsole() {
        val layout = detailLayoutFor(widthDp = 560f, heightDp = 272f, aspectRatio = gba)
        assertEquals(gba, layout.artworkWidthDp / layout.artworkHeightDp, tolerance)
        val portrait = detailLayoutFor(widthDp = 360f, heightDp = 472f, aspectRatio = gba)
        assertEquals(gba, portrait.artworkWidthDp / portrait.artworkHeightDp, tolerance)
    }

    @Test
    fun unmeasuredSpaceFallsBackToOneColumn() {
        val layout = detailLayoutFor(widthDp = 0f, heightDp = 0f, aspectRatio = gb)
        assertFalse(layout.twoColumns)
        assertEquals(0f, layout.artworkWidthDp, tolerance)
    }

    @Test
    fun theConsoleOfTheRomGivesTheProportion() {
        fun rom(name: String) = RomEntry(name, "content://$name", name, name, false, 32768, true, null)
        assertEquals(gb, rom("Pokemon Red.gb").screenAspectRatio, tolerance)
        assertEquals(gb, rom("Pokemon Gold.gbc").screenAspectRatio, tolerance)
        assertEquals(gba, rom("Kirby.gba").screenAspectRatio, tolerance)
    }
}
