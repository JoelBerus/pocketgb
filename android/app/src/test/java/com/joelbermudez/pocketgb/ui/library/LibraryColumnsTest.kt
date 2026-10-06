package com.joelbermudez.pocketgb.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryColumnsTest {
    @Test
    fun normalFontUsesTheAdaptiveCount() {
        assertEquals(2, columnsFor(widthDp = 360f, fontScale = 1.0f))
        assertEquals(3, columnsFor(widthDp = 480f, fontScale = 1.0f))
        assertEquals(1, columnsFor(widthDp = 150f, fontScale = 1.0f))
    }

    @Test
    fun fontScaleBelowOnePointThreeKeepsTheAdaptiveGrid() {
        assertEquals(columnsFor(412f, 1.0f), columnsFor(412f, 1.29f))
    }

    @Test
    fun mediumFontScalesUseTwoColumnsOnAPhone() {
        assertEquals(2, columnsFor(widthDp = 360f, fontScale = 1.3f))
        assertEquals(2, columnsFor(widthDp = 412f, fontScale = 1.5f))
        assertEquals(2, columnsFor(widthDp = 599f, fontScale = 1.7f))
    }

    @Test
    fun largeFontUsesASingleColumnOnAPhone() {
        assertEquals(1, columnsFor(widthDp = 360f, fontScale = 1.8f))
        assertEquals(1, columnsFor(widthDp = 412f, fontScale = 2.0f))
        assertEquals(1, columnsFor(widthDp = 599f, fontScale = 3.0f))
    }

    @Test
    fun wideWindowsGrowTheCellWithTheFontButKeepSeveralColumns() {
        assertEquals(3, columnsFor(widthDp = 1000f, fontScale = 2.0f))
        assertTrue(columnsFor(widthDp = 1000f, fontScale = 1.0f) >= columnsFor(widthDp = 1000f, fontScale = 2.0f))
    }

    @Test
    fun neverReturnsLessThanOneColumn() {
        assertEquals(1, columnsFor(widthDp = 0f, fontScale = 1.0f))
        assertEquals(1, columnsFor(widthDp = 100f, fontScale = 2.0f))
    }

    @Test
    fun largeFontFlagStartsAtOnePointFive() {
        assertFalse(isLargeFont(1.49f))
        assertTrue(isLargeFont(1.5f))
        assertTrue(isLargeFont(2.0f))
    }
}
