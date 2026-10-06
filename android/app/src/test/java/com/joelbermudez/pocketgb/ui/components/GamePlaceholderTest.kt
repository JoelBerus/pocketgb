package com.joelbermudez.pocketgb.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePlaceholderTest {
    @Test
    fun fnv1aMatchesPublishedVectors() {
        assertEquals(0x811C9DC5L, PlaceholderSeed.value(""))
        assertEquals(0xE40C292CL, PlaceholderSeed.value("a"))
        assertEquals(0xBF9CF968L, PlaceholderSeed.value("foobar"))
    }

    @Test
    fun sameKeyGivesSameColorGlyphAndInitials() {
        val key = "ab".repeat(32)
        assertEquals(PlaceholderSeed.colorIndex(key), PlaceholderSeed.colorIndex(key))
        assertEquals(PlaceholderSeed.glyph(key), PlaceholderSeed.glyph(key))
        assertEquals(PlaceholderSeed.value(key) % 4, PlaceholderSeed.colorIndex(key).toLong())
        assertEquals("DA", PlaceholderSeed.initials("DMG-ACID2"))
        assertEquals("?", PlaceholderSeed.initials("!!!"))
    }

    @Test
    fun glyphIsSymmetricWithACenterCell() {
        for (key in listOf("a", "b", "Pokemon Red.gb", "ff".repeat(32))) {
            val glyph = PlaceholderSeed.glyph(key)
            glyph.forEach { row -> for (c in 0 until 2) assertEquals(row[c], row[4 - c]) }
            assertTrue(glyph[2][2])
        }
    }
}
