package com.jarvis.os.desktop.brain

import com.jarvis.os.desktop.brain.TaskDates.Bucket
import com.jarvis.os.desktop.brain.TaskDates.Quick
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class TaskDatesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    // Monday 28 Sep 2026, 14:00.
    private val now = at("2026-09-28T14:00")

    @Test
    fun buckets() {
        assertEquals(Bucket.NO_DATE, TaskDates.bucket(null, now, zone))
        assertEquals(Bucket.OVERDUE, TaskDates.bucket(at("2026-09-28T09:00"), now, zone))
        assertEquals(Bucket.TODAY, TaskDates.bucket(at("2026-09-28T23:59"), now, zone))
        assertEquals(Bucket.UPCOMING, TaskDates.bucket(at("2026-09-29T00:00"), now, zone))
    }

    @Test
    fun labels() {
        val en = Locale.UK
        assertEquals("Today 18:00", TaskDates.label(at("2026-09-28T18:00"), now, zone, en))
        assertEquals("Tomorrow 09:00", TaskDates.label(at("2026-09-29T09:00"), now, zone, en))
        assertEquals("Yesterday 10:30", TaskDates.label(at("2026-09-27T10:30"), now, zone, en))
        assertEquals("Fri 2 Oct", TaskDates.label(at("2026-10-02T09:00"), now, zone, en))
        assertEquals("Mon 4 Jan 2027", TaskDates.label(at("2027-01-04T09:00"), now, zone, en))
    }

    @Test
    fun quickChoices() {
        assertEquals(at("2026-09-28T18:00"), TaskDates.quick(Quick.TODAY, now, zone))
        assertEquals(at("2026-09-29T09:00"), TaskDates.quick(Quick.TOMORROW, now, zone))
        assertEquals(at("2026-10-05T09:00"), TaskDates.quick(Quick.NEXT_WEEK, now, zone))
    }

    @Test
    fun todayLateInTheEveningStillLandsTodayOrSoon() {
        val late = at("2026-09-28T19:30")
        assertEquals(at("2026-09-28T21:30"), TaskDates.quick(Quick.TODAY, late, zone))
    }

    @Test
    fun endOfDayIsMidnight() {
        assertEquals(at("2026-09-29T00:00"), TaskDates.endOfDay(now, zone))
    }
}
