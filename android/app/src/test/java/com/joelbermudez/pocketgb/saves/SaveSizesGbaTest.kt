package com.joelbermudez.pocketgb.saves

import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.GbaSaveType
import com.joelbermudez.pocketgb.emulator.RomInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N8 · regla 6: tamaños de `.sav` de GBA = medio crudo (+16 B de RTC al final), idénticos a iOS `validSaveSizes`. */
class SaveSizesGbaTest {
    private fun gba(type: GbaSaveType, bytes: Int, rtc: Boolean, eepromFixed: Boolean = false) = RomInfo(
        title = "PGBA", cgbFlag = 0, cartType = 0, romBytes = 0x200, sramBytes = bytes, hasBattery = bytes > 0 || rtc,
        hasRtc = rtc, headerChecksumOk = true, globalChecksumOk = true, cgbMode = false, cgbCompat = false,
        fingerprint = ByteArray(32), console = Console.GBA, gbaSaveType = type, eeprom = type.isEeprom,
        eepromSizeFixed = eepromFixed,
    )

    @Test
    fun everyMediumWithAndWithoutTheClock() {
        assertEquals(setOf(32768), SaveSizes.forInfo(gba(GbaSaveType.SRAM, 32768, rtc = false)))
        assertEquals(setOf(32768, 32784), SaveSizes.forInfo(gba(GbaSaveType.SRAM, 32768, rtc = true)))
        assertEquals(setOf(65536, 65552), SaveSizes.forInfo(gba(GbaSaveType.FLASH64, 65536, rtc = true)))
        assertEquals(setOf(131072), SaveSizes.forInfo(gba(GbaSaveType.FLASH128, 131072, rtc = false)))
        // EEPROM sin ajuste: 512 B u 8 KiB (el `.sav` o la primera DMA lo deciden).
        assertEquals(setOf(512, 8192), SaveSizes.forInfo(gba(GbaSaveType.EEPROM512, 512, rtc = false)))
        assertEquals(setOf(512, 8192, 528, 8208), SaveSizes.forInfo(gba(GbaSaveType.EEPROM512, 512, rtc = true)))
        // Con el ajuste del juego fijando el tamaño, solo ese.
        assertEquals(setOf(8192, 8208), SaveSizes.forInfo(gba(GbaSaveType.EEPROM8K, 8192, rtc = true, eepromFixed = true)))
        // Sin medio: solo el reloj (16 B) o nada.
        assertEquals(setOf(16), SaveSizes.forInfo(gba(GbaSaveType.NONE, 0, rtc = true)))
        assertEquals(emptySet<Int>(), SaveSizes.forInfo(gba(GbaSaveType.NONE, 0, rtc = false)))
    }

    @Test
    fun theyAreExactlyTheOnesTheSessionAccepts() {
        val cases = listOf(
            gba(GbaSaveType.SRAM, 32768, true), gba(GbaSaveType.EEPROM512, 512, true), gba(GbaSaveType.EEPROM8K, 8192, false, true),
            gba(GbaSaveType.NONE, 0, true), gba(GbaSaveType.NONE, 0, false), gba(GbaSaveType.FLASH128, 131072, true),
        )
        for (info in cases) assertEquals("$info", info.gbaValidSaveSizes, SaveSizes.forInfo(info))
        // Nunca 0 y nunca más que el tope de lectura de una partida.
        for (info in cases) assertTrue(SaveSizes.forInfo(info).all { it in 1..SaveStore.MAX_SAVE_BYTES })
    }

    @Test
    fun theClockFooterIsNotPartOfTheCartridgeRam() {
        assertEquals(16, SaveSizes.footerBytes(gba(GbaSaveType.EEPROM512, 512, rtc = true), 8208))
        assertEquals(0, SaveSizes.footerBytes(gba(GbaSaveType.SRAM, 32768, rtc = false), 32768))
        val gb = RomInfo("RED", 0, 0x10, 0x8000, 8192, true, true, true, true, false, false, ByteArray(32))
        assertEquals(48, SaveSizes.footerBytes(gb, 8192 + 48))
        assertEquals(44, SaveSizes.footerBytes(gb, 8192 + 44))
        assertEquals("GB no cambia", setOf(8192, 8240, 8236), SaveSizes.forInfo(gb))
    }

    @Test
    fun forcedSettingsThatDoNotMatchAnExistingSaveWarnAndNeverTouchIt() {
        assertNull("sin ajustes forzados nunca avisa", GameSettingsSaveCheck.check(false, setOf(512), listOf(32768)))
        assertNull("coincide", GameSettingsSaveCheck.check(true, setOf(32768, 32784), listOf(32784)))
        assertNull("no hay .sav", GameSettingsSaveCheck.check(true, setOf(32768), emptyList()))
        assertEquals(SaveLoadWarning.GameSettingsMismatch(noSave = false), GameSettingsSaveCheck.check(true, setOf(65536), listOf(32768)))
        assertEquals(SaveLoadWarning.GameSettingsMismatch(noSave = true), GameSettingsSaveCheck.check(true, emptySet(), listOf(-1)))
    }
}
