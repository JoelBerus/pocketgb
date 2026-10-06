package com.joelbermudez.pocketgb.saves

/** Tamaños de `.sav` que acepta `gb_sram_load` (docs/03 §RTC: RAM, RAM+48 o el bloque antiguo RAM+44). */
object SaveSizes {
    private const val RTC_BLOCK = 48
    private const val LEGACY_RTC_BLOCK = 44

    fun validSizes(hasRtc: Boolean, sramBytes: Int): Set<Int> {
        val sizes = if (hasRtc) setOf(sramBytes, sramBytes + RTC_BLOCK, sramBytes + LEGACY_RTC_BLOCK) else setOf(sramBytes)
        // Un archivo vacío nunca es una partida válida.
        return sizes.filter { it > 0 }.toSet()
    }
}
