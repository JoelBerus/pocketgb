package com.joelbermudez.pocketgb.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N3a · carril «Continuar jugando»: el ancho de cada tarjeta sale de las mismas columnas que la cuadrícula
 * ([columnsFor]), con el mismo margen y separación, y en horizontal la tarjeta cabe en el alto disponible.
 */
class RailMetricsTest {
    private val tolerance = 0.01f

    /** Ancho que ocupan [columns] tarjetas con su separación: debe llenar exactamente el ancho útil (sin recorte). */
    private fun usedWidth(metrics: RailMetrics) =
        metrics.columns * metrics.cardWidthDp + (metrics.columns - 1) * GRID_SPACING_DP

    @Test
    fun cellWidthSharesTheGridMarginAndSpacing() {
        assertEquals(158f, gridCellWidth(360f, 2), tolerance)
        assertEquals(168f, gridCellWidth(560f, 3), tolerance)
        assertEquals(328f, gridCellWidth(360f, 1), tolerance)
        assertEquals(0f, gridCellWidth(10f, 2), tolerance)
    }

    @Test
    fun portraitPhoneShowsAsManyCardsAsGridColumns() {
        val metrics = railMetricsFor(widthDp = 360f, viewportHeightDp = 470f, fontScale = 1f, landscape = false)
        assertEquals(columnsFor(360f, 1f), metrics.columns)
        assertEquals(2, metrics.columns)
        assertEquals(gridCellWidth(360f, 2), metrics.cardWidthDp, tolerance)
        assertEquals(360f - 2 * GRID_MARGIN_DP, usedWidth(metrics), tolerance)
        assertEquals("sin límite de alto en vertical", metrics.cardWidthDp, metrics.artworkWidthDp, tolerance)
        assertFalse(metrics.stacked)
        assertFalse(metrics.horizontalCards)
    }

    @Test
    fun landscapePhoneUsesTheLandscapeGridColumns() {
        // 640 dp de ancho menos el NavigationRail (80 dp).
        val metrics = railMetricsFor(widthDp = 560f, viewportHeightDp = 272f, fontScale = 1f, landscape = true)
        assertEquals(3, metrics.columns)
        assertEquals(gridCellWidth(560f, 3), metrics.cardWidthDp, tolerance)
        assertEquals(560f - 2 * GRID_MARGIN_DP, usedWidth(metrics), tolerance)
        assertTrue("la tarjeta cabe en el alto", metrics.estimatedHeightDp <= 272f)
    }

    @Test
    fun wideWindowFollowsTheWideGrid() {
        val metrics = railMetricsFor(widthDp = 773f, viewportHeightDp = 445f, fontScale = 1f, landscape = true)
        assertEquals(columnsFor(773f, 1f), metrics.columns)
        assertEquals(4, metrics.columns)
        assertEquals(773f - 2 * GRID_MARGIN_DP, usedWidth(metrics), tolerance)
    }

    @Test
    fun landscapeCapsTheArtworkSoTheCardFitsTheAvailableHeight() {
        // Poco alto: la portada (10:9) se limita y la tarjeta sigue ocupando su columna.
        val metrics = railMetricsFor(widthDp = 560f, viewportHeightDp = 200f, fontScale = 1f, landscape = true)
        assertEquals(gridCellWidth(560f, 3), metrics.cardWidthDp, tolerance)
        assertTrue("portada más estrecha que la columna", metrics.artworkWidthDp < metrics.cardWidthDp)
        assertTrue("cabe: ${metrics.estimatedHeightDp}", metrics.estimatedHeightDp <= 200f + tolerance)
        assertTrue(metrics.artworkHeightDp <= 200f)
    }

    @Test
    fun theArtworkCapNeverShrinksBelowAUsableSize() {
        val metrics = railMetricsFor(widthDp = 560f, viewportHeightDp = 40f, fontScale = 1f, landscape = true)
        assertEquals(RAIL_MIN_ARTWORK_DP, metrics.artworkWidthDp, tolerance)
    }

    @Test
    fun largeFontInPortraitStacksHorizontalCardsInOneColumn() {
        val metrics = railMetricsFor(widthDp = 360f, viewportHeightDp = 470f, fontScale = 2f, landscape = false)
        assertEquals(1, metrics.columns)
        assertTrue(metrics.stacked)
        assertTrue(metrics.horizontalCards)
        assertEquals(gridCellWidth(360f, 1), metrics.cardWidthDp, tolerance)
    }

    @Test
    fun largeFontInLandscapeIsARowOfOneCardThatFitsTheHeight() {
        val metrics = railMetricsFor(widthDp = 560f, viewportHeightDp = 272f, fontScale = 2f, landscape = true)
        assertEquals(1, metrics.columns)
        assertFalse("en horizontal no se apilan (no cabrían)", metrics.stacked)
        assertTrue(metrics.horizontalCards)
        assertEquals(560f - 2 * GRID_MARGIN_DP, usedWidth(metrics), tolerance)
        assertTrue("la portada cabe: ${metrics.artworkHeightDp}", metrics.estimatedHeightDp <= 272f + tolerance)
    }

    @Test
    fun mediumFontKeepsTwoColumnsAndVerticalCards() {
        val metrics = railMetricsFor(widthDp = 360f, viewportHeightDp = 470f, fontScale = 1.3f, landscape = false)
        assertEquals(2, metrics.columns)
        assertFalse(metrics.horizontalCards)
        assertFalse(metrics.stacked)
    }

    @Test
    fun largeFontInAWideWindowKeepsTheWideGridColumns() {
        val metrics = railMetricsFor(widthDp = 1000f, viewportHeightDp = 700f, fontScale = 2f, landscape = true)
        assertEquals(columnsFor(1000f, 2f), metrics.columns)
        assertEquals(3, metrics.columns)
        assertFalse(metrics.stacked)
        assertTrue(metrics.horizontalCards)
        assertTrue("la portada no llena la tarjeta horizontal", metrics.artworkWidthDp <= metrics.cardWidthDp * 0.5f)
    }

    @Test
    fun landscapeIsDecidedByTheAvailableSpace() {
        assertTrue(isLandscapeLibrary(widthDp = 560f, heightDp = 312f))
        assertTrue(isLandscapeLibrary(widthDp = 773f, heightDp = 509f))
        assertFalse(isLandscapeLibrary(widthDp = 360f, heightDp = 536f))
        assertFalse(isLandscapeLibrary(widthDp = 600f, heightDp = 600f))
        assertFalse("sin medir", isLandscapeLibrary(widthDp = 0f, heightDp = 0f))
    }
}
