package com.jarvis.os.desktop.brain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * When a routine runs (AGENT_PLAN S9, "every weekday at 8, brief me"): a set of weekdays and
 * a local time. Stored as text ("MON,TUE,WED,THU,FRI@08:00") so it reads plainly in the
 * database and survives time-zone changes: the next run is always computed in the laptop's
 * current zone. Pure and tested (ScheduleTest).
 */
data class Schedule(val days: Set<DayOfWeek>, val time: LocalTime) {

    fun encode(): String = DayOfWeek.entries.filter { it in days }.joinToString(",") { it.name.take(3) } + "@" + time.toString()

    /** The first run strictly after [afterMs]. */
    fun next(afterMs: Long, zone: ZoneId): Long {
        val after = Instant.ofEpochMilli(afterMs).atZone(zone)
        var d = after.toLocalDate()
        repeat(8) {
            if (d.dayOfWeek in days) {
                val at = d.atTime(time).atZone(zone)
                if (at.isAfter(after)) return at.toInstant().toEpochMilli()
            }
            d = d.plusDays(1)
        }
        error("a schedule always has a day")   // days is never empty (see parse)
    }

    /** "Weekdays at 08:00", "Every day at 07:30", "Mon, Thu at 18:00". */
    fun describe(): String {
        val t = time.toString()
        return when (days) {
            ALL -> "Every day at $t"
            WEEKDAYS -> "Weekdays at $t"
            WEEKEND -> "Weekends at $t"
            else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") { it.name.take(1) + it.name.substring(1, 3).lowercase() } + " at $t"
        }
    }

    /** What to do with a run that came due at [dueMs], seen at [nowMs] (the laptop may have been off). */
    enum class Due { RUN, RUN_LATE, SKIP }

    companion object {
        val ALL: Set<DayOfWeek> = DayOfWeek.entries.toSet()
        val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        /** A run missed by more than this (laptop off, asleep) is skipped, not run hours late. */
        const val LATE_LIMIT_MS = 6 * 60 * 60 * 1000L
        /** Up to this late it still counts as on time (the checker ticks every 15 s). */
        const val ON_TIME_MS = 5 * 60 * 1000L

        fun due(dueMs: Long, nowMs: Long): Due = when {
            nowMs - dueMs <= ON_TIME_MS -> Due.RUN
            nowMs - dueMs <= LATE_LIMIT_MS -> Due.RUN_LATE
            else -> Due.SKIP
        }

        fun decode(s: String): Schedule? {
            val (d, t) = s.split('@').takeIf { it.size == 2 } ?: return null
            val days = d.split(',').mapNotNull { day(it) }.toSet().ifEmpty { return null }
            val time = time(t) ?: return null
            return Schedule(days, time)
        }

        /**
         * The model's words → a schedule, or null if either part is unreadable.
         * Days: "weekdays", "weekends", "daily"/"every day", or a list ("mon, thu", "Monday and Friday").
         * Time: "08:00", "8:00", "8", "8am", "6:30 pm", "18:30".
         */
        fun parse(days: String, time: String): Schedule? {
            val t = time(time) ?: return null
            val d = days.trim().lowercase()
            val set = when {
                d.isEmpty() || d in setOf("daily", "every day", "everyday", "all", "each day") -> ALL
                d.contains("weekday") -> WEEKDAYS
                d.contains("weekend") -> WEEKEND
                else -> Regex("[a-z]+").findAll(d).mapNotNull { day(it.value) }.toSet()
            }
            return if (set.isEmpty()) null else Schedule(set, t)
        }

        private fun day(s: String): DayOfWeek? {
            val w = s.trim().lowercase()
            if (w.length < 2) return null
            return DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(w.take(3)) && (w.length <= 3 || it.name.lowercase().startsWith(w.removeSuffix("s"))) }
        }

        private fun time(s: String): LocalTime? {
            val raw = s.trim().lowercase().replace(".", ":").replace(" ", "")
            val pm = raw.endsWith("pm")
            val am = raw.endsWith("am")
            val core = raw.removeSuffix("pm").removeSuffix("am")
            val parts = core.split(':')
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val m = parts.getOrNull(1)?.take(2)?.toIntOrNull() ?: 0
            if (parts.size > 1 && parts[1].isNotEmpty() && parts[1].take(2).toIntOrNull() == null) return null
            val hour = when {
                pm && h in 1..11 -> h + 12
                am && h == 12 -> 0
                else -> h
            }
            return try { LocalTime.of(hour, m) } catch (e: java.time.DateTimeException) { null } catch (e: DateTimeParseException) { null }
        }
    }
}
