package com.jarvis.os.desktop.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ScheduleTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().toString()

    @Test
    fun everyWeekdayAtEight_S9() {
        val s = Schedule.parse("weekdays", "8:00")!!
        assertEquals(Schedule.WEEKDAYS, s.days)
        assertEquals("Weekdays at 08:00", s.describe())
        // Monday 28 Sep 2026, 14:00 → Tuesday 08:00.
        assertEquals("2026-09-29T08:00", iso(s.next(at("2026-09-28T14:00"), zone)))
        // Friday evening → Monday, skipping the weekend.
        assertEquals("2026-10-05T08:00", iso(s.next(at("2026-10-02T19:00"), zone)))
        // Exactly at the time → the NEXT one (never runs twice).
        assertEquals("2026-09-30T08:00", iso(s.next(at("2026-09-29T08:00"), zone)))
        // Before the time on a weekday → today.
        assertEquals("2026-09-29T08:00", iso(s.next(at("2026-09-29T07:59"), zone)))
    }

    @Test
    fun theWaysPeopleSayDaysAndTimes() {
        assertEquals(Schedule.ALL, Schedule.parse("daily", "7:30")!!.days)
        assertEquals(Schedule.ALL, Schedule.parse("every day", "07:30")!!.days)
        assertEquals(Schedule.WEEKEND, Schedule.parse("weekends", "10")!!.days)
        assertEquals(setOf(MONDAY, THURSDAY), Schedule.parse("Mondays and thurs", "18:00")!!.days)
        assertEquals(setOf(MONDAY, FRIDAY), Schedule.parse("mon, fri", "6pm")!!.days)
        assertEquals(LocalTime.of(18, 0), Schedule.parse("mon", "6pm")!!.time)
        assertEquals(LocalTime.of(18, 30), Schedule.parse("mon", "6:30 PM")!!.time)
        assertEquals(LocalTime.of(0, 15), Schedule.parse("mon", "12:15am")!!.time)
        assertEquals(LocalTime.of(12, 0), Schedule.parse("mon", "12pm")!!.time)
        assertEquals(LocalTime.of(8, 0), Schedule.parse("mon", "8")!!.time)
        assertNull(Schedule.parse("mon", "25:00"))
        assertNull(Schedule.parse("mon", "soon"))
        assertNull(Schedule.parse("someday", "8:00"))
    }

    @Test
    fun itRoundTripsThroughTheDatabaseText() {
        val s = Schedule.parse("mon, thu", "18:05")!!
        assertEquals("MON,THU@18:05", s.encode())
        assertEquals(s, Schedule.decode(s.encode()))
        assertEquals("Mon, Thu at 18:05", s.describe())
        assertNull(Schedule.decode("garbage"))
        assertNull(Schedule.decode("@08:00"))
    }

    @Test
    fun missedRunsAreRunLateOrSkippedNeverFiredHoursLate() {
        val due = at("2026-09-29T08:00")
        assertEquals(Schedule.Due.RUN, Schedule.due(due, due + 30_000))
        assertEquals(Schedule.Due.RUN_LATE, Schedule.due(due, due + 2 * 3_600_000))
        assertEquals(Schedule.Due.SKIP, Schedule.due(due, due + 7 * 3_600_000))
    }
}
