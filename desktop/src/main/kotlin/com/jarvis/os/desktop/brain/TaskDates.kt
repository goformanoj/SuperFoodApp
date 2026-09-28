package com.jarvis.os.desktop.brain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * How tasks are grouped and labelled by date — pure, so it is unit-tested (TaskDatesTest)
 * with a fixed clock and zone rather than whatever day the test happens to run on.
 */
object TaskDates {

    enum class Bucket(val label: String) { OVERDUE("Overdue"), TODAY("Today"), UPCOMING("Upcoming"), NO_DATE("No date") }

    fun bucket(dueAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Bucket {
        if (dueAt == null) return Bucket.NO_DATE
        if (dueAt < now) return Bucket.OVERDUE
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val due = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        return if (due == today) Bucket.TODAY else Bucket.UPCOMING
    }

    /** "Today 18:00", "Tomorrow 09:00", "Tue 30 Sep", "Tue 30 Sep 2027" (other years). */
    fun label(dueAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val t = Instant.ofEpochMilli(dueAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = t.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        return when (t.toLocalDate()) {
            today -> "Today $time"
            today.plusDays(1) -> "Tomorrow $time"
            today.minusDays(1) -> "Yesterday $time"
            else -> t.format(DateTimeFormatter.ofPattern(if (t.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", locale))
        }
    }

    /** Quick due-date choices when adding a task by hand. */
    enum class Quick(val label: String) { TODAY("Today"), TOMORROW("Tomorrow"), NEXT_WEEK("Next week") }

    /** Today → 18:00 today (or +2 h if that's past); Tomorrow → 09:00; Next week → Monday 09:00. */
    fun quick(q: Quick, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val n = Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
        val at: LocalDateTime = when (q) {
            Quick.TODAY -> n.toLocalDate().atTime(18, 0).let { if (it.isAfter(n)) it else n.plusHours(2).withSecond(0).withNano(0) }
            Quick.TOMORROW -> n.toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0))
            Quick.NEXT_WEEK -> n.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(9, 0)
        }
        return at.atZone(zone).toInstant().toEpochMilli()
    }

    fun endOfDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    @Suppress("unused")
    private fun LocalDate.ms(zone: ZoneId) = atStartOfDay(zone).toInstant().toEpochMilli()
}
