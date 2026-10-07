package com.joelbermudez.pocketgb.saves

import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.GBA_RTC_BYTES
import com.joelbermudez.pocketgb.emulator.RomInfo

/**
 * Tamaños de `.sav` que acepta el núcleo. Game Boy (`gb_sram_load`, docs/03 §RTC): RAM, RAM+48 o el bloque antiguo
 * RAM+44. Game Boy Advance (N8, = iOS `GBACoreBridge.validSaveSizes`, G7-3): el medio crudo (EEPROM sin ajuste: 512 B u
 * 8 KiB) y, con RTC, también el medio + 16 bytes del reloj al final; con un medio de 0 bytes y RTC, solo los 16.
 */
object SaveSizes {
    private const val RTC_BLOCK = 48
    private const val LEGACY_RTC_BLOCK = 44

    const val EEPROM_SMALL = 512
    const val EEPROM_LARGE = 8192

    fun validSizes(hasRtc: Boolean, sramBytes: Int): Set<Int> {
        val sizes = if (hasRtc) setOf(sramBytes, sramBytes + RTC_BLOCK, sramBytes + LEGACY_RTC_BLOCK) else setOf(sramBytes)
        // Un archivo vacío nunca es una partida válida.
        return sizes.filter { it > 0 }.toSet()
    }

    /**
     * Game Boy Advance: el medio ([mediaBytes]; con [eepromAnySize], 512 o 8192) y, con [hasRtc], cada uno + 16. Sin
     * medio y con RTC, solo los 16 bytes del reloj. Nunca incluye 0.
     */
    fun gbaValidSizes(mediaBytes: Int, hasRtc: Boolean, eepromAnySize: Boolean = false): Set<Int> {
        val media = when {
            eepromAnySize -> setOf(EEPROM_SMALL, EEPROM_LARGE)
            mediaBytes > 0 -> setOf(mediaBytes)
            else -> emptySet()
        }
        if (!hasRtc) return media
        return if (media.isEmpty()) setOf(GBA_RTC_BYTES) else media + media.map { it + GBA_RTC_BYTES }
    }

    /** Los de un ROM ya cargado: [validSizes] en GB y [gbaValidSizes] (= [RomInfo.gbaValidSaveSizes]) en GBA. */
    fun forInfo(info: RomInfo): Set<Int> = when (info.console) {
        Console.GB -> validSizes(info.hasRtc, info.sramBytes)
        Console.GBA -> gbaValidSizes(info.sramBytes, info.hasRtc, eepromAnySize = info.eeprom && !info.eepromSizeFixed)
    }

    /**
     * Bytes del pie del reloj al final de un `.sav` de [saveSize] bytes: lo que NO cuenta como «la partida» al comparar
     * un estado automático con la RAM (iOS `sramFooterBytes`, INT-H1). GBA: 16 con RTC. GB: lo que pase de la RAM.
     */
    fun footerBytes(info: RomInfo, saveSize: Int): Int = when (info.console) {
        Console.GBA -> if (info.hasRtc) GBA_RTC_BYTES.coerceAtMost(saveSize) else 0
        Console.GB -> (saveSize - info.sramBytes).coerceAtLeast(0)
    }
}
