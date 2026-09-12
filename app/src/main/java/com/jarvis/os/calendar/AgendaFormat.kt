package com.jarvis.os.calendar

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The date/time wording the Calendar screen shows — kept out of the Compose layer
 * so the fiddly parts (day boundaries, "Today/Tomorrow", the up-next countdown) are
 * pure and unit-tested. Day maths is LOCAL, because a calendar day is the user's
 * day; tests inject a fixed zone so the boundaries are deterministic.
 */
object AgendaFormat {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Whole local days from [ref]'s day to [target]'s day (0 = same day, 1 = tomorrow). */
    fun dayDifference(target: Long, ref: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val diff = floorDay(target, zone) - floorDay(ref, zone)
        return Math.floorDiv(diff, DAY_MS).toInt()
    }

    /** "Today", "Tomorrow", else e.g. "Thursday 11 Sep". */
    fun dayLabel(
        startMillis: Long,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = when (dayDifference(startMillis, now, zone)) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> SimpleDateFormat("EEEE d MMM", locale).apply { timeZone = zone }.format(Date(startMillis))
    }

    /** Short weekday + day-of-month for the week strip, e.g. "Tue" / "9". */
    fun weekdayShort(millis: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("EEE", locale).apply { timeZone = zone }.format(Date(millis))

    fun dayOfMonth(millis: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(zone); cal.timeInMillis = millis
        return cal.get(Calendar.DAY_OF_MONTH)
    }

    /** "now", "in 8 min", "in 2 h 10 m", "in 3 days" — how far off an event is. */
    fun countdown(deltaMs: Long): String {
        if (deltaMs <= 0) return "now"
        val min = deltaMs / 60_000
        return when {
            min < 1 -> "now"
            min < 60 -> "in $min min"
            min < 24 * 60 -> {
                val h = min / 60; val m = min % 60
                if (m == 0L) "in $h h" else "in $h h $m m"
            }
            else -> {
                val d = min / (24 * 60)
                "in $d day" + if (d > 1) "s" else ""
            }
        }
    }

    /** Index of the next event still to come, or null when the day is done. */
    fun nextUpIndex(events: List<CalendarReader.Event>, now: Long): Int? {
        val i = events.indexOfFirst { it.startMillis > now }
        return if (i >= 0) i else null
    }

    /** Midnight (local) of the day [ms] falls in. */
    private fun floorDay(ms: Long, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
