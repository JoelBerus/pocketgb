package com.joelbermudez.pocketgb.library

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeDateTest {
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 10, 6, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun describesRecentAndOlderMoments() {
        assertEquals(RelativeDate.Now, RelativeDate.of(now - 30_000, now, zone))
        assertEquals(RelativeDate.Now, RelativeDate.of(now + 5_000, now, zone))
        assertEquals(RelativeDate.Minutes(5), RelativeDate.of(now - 5 * 60_000, now, zone))
        assertEquals(RelativeDate.Hours(3), RelativeDate.of(at(6, 12), now, zone))
        assertEquals(RelativeDate.Yesterday, RelativeDate.of(at(5, 23, 30), now, zone))
        assertEquals(RelativeDate.Days(3), RelativeDate.of(at(3, 9), now, zone))
        val old = ZonedDateTime.of(2026, 9, 20, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(RelativeDate.Absolute(old), RelativeDate.of(old, now, zone))
        assertEquals(RelativeDate.Days(6), RelativeDate.of(now - 6 * 24 * 3_600_000L, now, zone))
    }
}
