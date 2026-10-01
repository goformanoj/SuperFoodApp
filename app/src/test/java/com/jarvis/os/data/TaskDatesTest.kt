package com.jarvis.os.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TaskDatesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-09-28T14:00")

    @Test
    fun labelsTodayTomorrowYesterdayAndOtherDays() {
        assertEquals("Today 18:00", TaskDates.label(at("2026-09-28T18:00"), now, zone))
        assertEquals("Tomorrow 09:00", TaskDates.label(at("2026-09-29T09:00"), now, zone))
        assertEquals("Yesterday 09:00", TaskDates.label(at("2026-09-27T09:00"), now, zone))
        assertEquals("Mon 5 Oct", TaskDates.label(at("2026-10-05T09:00"), now, zone))
    }

    @Test
    fun aDifferentYearIsSpelledOut() {
        assertEquals("Fri 1 Jan 2027", TaskDates.label(at("2027-01-01T00:00"), now, zone))
    }

    @Test
    fun endOfDayIsTheStartOfTomorrow() {
        assertEquals(at("2026-09-29T00:00"), TaskDates.endOfDay(now, zone))
    }
}
