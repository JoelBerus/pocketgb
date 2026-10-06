package com.joelbermudez.pocketgb.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EmulationOptionsTest {
    @Test
    fun defaultsAreAutomatic() {
        val options = EmulationOptions()
        assertEquals(GbModel.AUTO, options.model)
        assertEquals(0, options.compatPalette)
    }

    @Test
    fun palettesFromAutoToTwelveAreAccepted() {
        for (id in 0..12) assertEquals(id, EmulationOptions(GbModel.CGB, id).compatPalette)
    }

    @Test
    fun outOfRangePalettesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EmulationOptions(compatPalette = 13) }
        assertThrows(IllegalArgumentException::class.java) { EmulationOptions(compatPalette = -1) }
    }

    @Test
    fun nativeValuesMatchTheCoreEnums() {
        assertEquals(listOf(0, 1, 2), GbModel.entries.map { it.native })
        assertEquals(listOf(0, 1), ScaleMode.entries.map { it.native })
    }
}
