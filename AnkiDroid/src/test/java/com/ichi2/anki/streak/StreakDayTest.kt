// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals

class StreakDayTest {
    @Test
    fun `rollover at nonexistent DST hour preserves the previous day's configured boundary`() {
        val zone = ZoneId.of("America/New_York")
        val cutoff = LocalDateTime.parse("2026-03-08T02:00").atZone(zone).toEpochSecond()
        val previous =
            LocalDateTime
                .parse("2026-03-07T02:00")
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        assertEquals(previous, StreakPlan.dayStart(cutoff, zone, 2))
    }

    @Test
    fun `rollover boundaries preserve month year and leap day`() {
        val zone = ZoneId.of("Asia/Singapore")
        for ((cutoff, start) in listOf(
            "2026-11-01T04:00" to "2026-10-31T04:00",
            "2027-01-01T04:00" to "2026-12-31T04:00",
            "2028-03-01T04:00" to "2028-02-29T04:00",
        )) {
            val end = LocalDateTime.parse(cutoff).atZone(zone).toEpochSecond()
            assertEquals(
                LocalDateTime
                    .parse(start)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli(),
                StreakPlan.dayStart(end, zone),
            )
        }
    }

    @Test
    fun `DST day is not assumed to last 86400 seconds`() {
        val zone = ZoneId.of("America/New_York")
        val end = LocalDateTime.parse("2026-03-08T04:00").atZone(zone).toEpochSecond()
        assertEquals(Duration.ofHours(23).toMillis(), end * 1000 - StreakPlan.dayStart(end, zone))
    }

    @Test
    fun `timezone and custom rollover use the scheduler supplied cutoff`() {
        for (name in listOf("Asia/Tokyo", "America/Los_Angeles", "Europe/London")) {
            val zone = ZoneId.of(name)
            val end = LocalDateTime.parse("2026-10-28T06:00").atZone(zone)
            assertEquals(end.minusDays(1).toInstant().toEpochMilli(), StreakPlan.dayStart(end.toEpochSecond(), zone, 6))
        }
    }
}
