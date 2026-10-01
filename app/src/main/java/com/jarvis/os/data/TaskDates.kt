package com.jarvis.os.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How a task's due date is shown to the model and the user — ported from the laptop's
 * `desktop/.../brain/TaskDates.kt`, trimmed to what the agent's tools actually need
 * ([label], [endOfDay]). Pure, so it is unit-tested with a fixed clock rather than
 * whatever day the test happens to run on.
 */
object TaskDates {

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

    fun endOfDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
