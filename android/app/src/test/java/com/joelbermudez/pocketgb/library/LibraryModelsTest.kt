package com.joelbermudez.pocketgb.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryModelsTest {
    @Test
    fun cartridgeNamesCoverCommonAndUnknownCodes() {
        assertEquals("Solo ROM", CartridgeNames.describe(0x00))
        assertEquals("MBC5 + RAM + batería", CartridgeNames.describe(0x1B))
        assertEquals("Pocket Camera", CartridgeNames.describe(0xFC))
        assertEquals("Desconocido (0x42)", CartridgeNames.describe(0x42))
    }

    @Test
    fun byteFormatUsesBinaryUnitsAndCommaDecimals() {
        assertEquals("512 B", ByteFormat.format(512))
        assertEquals("32 KiB", ByteFormat.format(32 * 1024))
        assertEquals("1,5 MiB", ByteFormat.format(1536 * 1024))
        assertEquals("8 MiB", ByteFormat.format(8L * 1024 * 1024))
    }

    @Test
    fun hiddenListsOnlyHiddenEntriesInTitleOrder() {
        fun entry(id: String) = RomEntry(id, "u/$id", id, id, com.joelbermudez.pocketgb.library.RomConsole.GB, 1, true, null)
        val a = entry("B.gb")
        val b = entry("A.gb")
        val c = entry("C.gb")
        val prefs = LibraryPreferencesData().hide(a).hide(b)
        assertEquals(listOf("A.gb", "B.gb"), LibraryQuery.hidden(listOf(a, b, c), prefs).map { it.id })
    }
}
