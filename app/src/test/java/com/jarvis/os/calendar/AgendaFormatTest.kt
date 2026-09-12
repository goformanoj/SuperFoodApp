package com.jarvis.os.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** The Calendar screen's wording — day boundaries and the up-next countdown, in a fixed UTC zone. */
class AgendaFormatTest {

    private val utc = TimeZone.getTimeZone("UTC")
    // 2026-09-09T12:00:00Z
    private val now = 1_757_419_200_000L
    private val DAY = 24L * 60 * 60 * 1000

    @Test
    fun `day difference counts local days, not 24h blocks`() {
        assertEquals(0, AgendaFormat.dayDifference(now + 3 * 60 * 60 * 1000, now, utc)) // later today
        assertEquals(0, AgendaFormat.dayDifference(now - 11 * 60 * 60 * 1000, now, utc)) // 1am today
        assertEquals(1, AgendaFormat.dayDifference(now + DAY, now, utc))
        assertEquals(-1, AgendaFormat.dayDifference(now - DAY, now, utc))
    }

    @Test
    fun `day label is Today, Tomorrow, or a named day`() {
        assertEquals("Today", AgendaFormat.dayLabel(now + 60_000, now, utc, Locale.US))
        assertEquals("Tomorrow", AgendaFormat.dayLabel(now + DAY, now, utc, Locale.US))
        val later = AgendaFormat.dayLabel(now + 3 * DAY, now, utc, Locale.US)
        assertTrue("expected a named day, got $later", later.matches(Regex("^[A-Za-z]+ \\d+ [A-Za-z]+$")))
    }

    @Test
    fun `countdown reads naturally at every scale`() {
        assertEquals("now", AgendaFormat.countdown(0))
        assertEquals("now", AgendaFormat.countdown(30_000))
        assertEquals("in 5 min", AgendaFormat.countdown(5 * 60_000))
        assertEquals("in 1 h 30 m", AgendaFormat.countdown(90 * 60_000))
        assertEquals("in 2 h", AgendaFormat.countdown(120 * 60_000))
        assertEquals("in 1 day", AgendaFormat.countdown(25L * 60 * 60_000))
        assertEquals("in 2 days", AgendaFormat.countdown(50L * 60 * 60_000))
    }

    @Test
    fun `next up is the first event still ahead`() {
        val events = listOf(
            CalendarReader.Event("Past", now - 60_000, false),
            CalendarReader.Event("Standup", now + 42 * 60_000, false),
            CalendarReader.Event("Lunch", now + 3 * 60 * 60_000, false),
        )
        assertEquals(1, AgendaFormat.nextUpIndex(events, now))
        assertNull(AgendaFormat.nextUpIndex(events.take(1), now)) // all in the past
    }
}
