package ism.dansha.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DatesTest {
    @Test
    fun payCycleStartsOn15th() {
        assertEquals("2026-09 Sep-Oct", Dates.payCycleOf("2026-10-03"))
        assertEquals("2026-09 Sep-Oct", Dates.payCycleOf("2026-10-14"))
        assertEquals("2026-10 Oct-Nov", Dates.payCycleOf("2026-10-15"))
        assertEquals("2026-12 Dec-Jan", Dates.payCycleOf("2026-12-31"))
        assertEquals("2025-12 Dec-Jan", Dates.payCycleOf("2026-01-01"))
    }

    @Test
    fun payCycleRange() {
        assertEquals(LocalDate.parse("2026-09-15") to LocalDate.parse("2026-10-14"), Dates.payCycleRange("2026-09 Sep-Oct"))
        assertEquals(LocalDate.parse("2026-12-15") to LocalDate.parse("2027-01-14"), Dates.payCycleRange("2026-12 Dec-Jan"))
        assertEquals(LocalDate.parse("2026-02-01") to LocalDate.parse("2026-02-28"), Dates.payCycleRange("2026-02 Feb-Mar", 1))
    }

    @Test
    fun alwaysBangkokTime() {
        val saved = Dates.clock
        try {
            // 2026-10-02 20:30 UTC = 2026-10-03 03:30 เวลาไทย
            Dates.clock = Clock.fixed(Instant.parse("2026-10-02T20:30:00Z"), java.time.ZoneOffset.UTC)
            assertEquals("2026-10-03", Dates.todayStr())
            assertEquals("2026-10-03T03:30:00", Dates.nowIso())
        } finally {
            Dates.clock = saved
        }
    }
}
