package com.joelbermudez.pocketgb.library

import java.time.Instant
import java.time.ZoneId

/** Cuándo ocurrió algo, en palabras. Puro (sin textos): la UI lo traduce con recursos. */
sealed interface RelativeDate {
    data object Now : RelativeDate

    data class Minutes(val count: Int) : RelativeDate

    data class Hours(val count: Int) : RelativeDate

    data object Yesterday : RelativeDate

    data class Days(val count: Int) : RelativeDate

    /** Más de una semana: fecha corta («5 oct»). */
    data class Absolute(val epochMs: Long) : RelativeDate

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE

        fun of(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): RelativeDate {
            val delta = nowMs - epochMs
            if (delta < MINUTE) return Now // también fechas futuras por relojes desajustados
            if (delta < HOUR) return Minutes((delta / MINUTE).toInt())
            val then = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val days = (today.toEpochDay() - then.toEpochDay()).toInt()
            return when {
                days <= 0 -> Hours((delta / HOUR).toInt().coerceAtLeast(1))
                days == 1 -> Yesterday
                days < 7 -> Days(days)
                else -> Absolute(epochMs)
            }
        }
    }
}
