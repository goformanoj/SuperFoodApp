package com.jarvis.os.alarm

import java.util.Calendar

/** An alarm or timer the AI asked for. */
sealed interface AlarmAction {
    /**
     * [days] is empty for a one-off alarm, otherwise `Calendar.MONDAY`-style
     * constants for a repeating one.
     */
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String,
        val days: List<Int> = emptyList(),
    ) : AlarmAction

    data class StartTimer(val seconds: Int, val label: String) : AlarmAction
}

/**
 * Parses alarm and timer markers out of an AI reply:
 *   <<ALARM|SET|07:30|Gym>>                     one-off
 *   <<ALARM|SET|07:30|Gym|MON,WED,FRI>>         repeating
 *   <<ALARM|TIMER|600|Pasta>>                   timer, in seconds
 *
 * Same shape as [com.jarvis.os.calendar.CalendarActions] on purpose, including
 * tolerating a single closing bracket — the model emits one often enough that a
 * rigid parser silently drops the action AND leaks the marker into speech.
 *
 * Pure Kotlin so the whole thing is unit-tested without a device.
 */
object AlarmActions {

    private val REGEX = Regex("""<<ALARM\|([^>\n]*)>{1,2}""", RegexOption.IGNORE_CASE)

    private val DAYS = mapOf(
        "MON" to Calendar.MONDAY,
        "TUE" to Calendar.TUESDAY,
        "WED" to Calendar.WEDNESDAY,
        "THU" to Calendar.THURSDAY,
        "FRI" to Calendar.FRIDAY,
        "SAT" to Calendar.SATURDAY,
        "SUN" to Calendar.SUNDAY,
    )

    /** Returns the reply with markers stripped, plus the actions found. */
    fun parse(reply: String): Pair<String, List<AlarmAction>> {
        val actions = mutableListOf<AlarmAction>()
        var clean = reply
        for (match in REGEX.findAll(reply)) {
            clean = clean.replace(match.value, "")
            val parts = match.groupValues[1].split("|").map { it.trim() }
            if (parts.isEmpty()) continue
            when (parts[0].uppercase()) {
                "SET", "ADD" -> parseAlarm(parts)?.let { actions.add(it) }
                "TIMER" -> parseTimer(parts)?.let { actions.add(it) }
            }
        }
        return clean.trim() to actions
    }

    private fun parseAlarm(parts: List<String>): AlarmAction.SetAlarm? {
        val time = parts.getOrNull(1) ?: return null
        val (hour, minute) = parseTime(time) ?: return null
        val label = parts.getOrNull(2)?.ifBlank { null } ?: "Alarm"
        val days = parts.getOrNull(3).orEmpty()
            .split(",")
            .mapNotNull { DAYS[it.trim().uppercase().take(3)] }
            .distinct()
        return AlarmAction.SetAlarm(hour, minute, label, days)
    }

    private fun parseTimer(parts: List<String>): AlarmAction.StartTimer? {
        val seconds = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (seconds <= 0) return null
        val label = parts.getOrNull(2)?.ifBlank { null } ?: "Timer"
        return AlarmAction.StartTimer(seconds, label)
    }

    /** 24-hour "HH:MM". Rejects anything that is not a real time of day. */
    fun parseTime(text: String): Pair<Int, Int>? {
        val bits = text.trim().split(":")
        if (bits.size != 2) return null
        val hour = bits[0].toIntOrNull() ?: return null
        val minute = bits[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
