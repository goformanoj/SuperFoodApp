package com.jarvis.os.calendar

import java.text.SimpleDateFormat
import java.util.Locale

/** A calendar command the AI asked to perform. */
sealed interface CalAction {
    data class Add(val title: String, val startMillis: Long, val durationMin: Int) : CalAction
    data class Delete(val title: String, val date: String, val time: String) : CalAction
}

/**
 * Parses calendar command markers out of an AI reply. The model emits, only
 * after the user confirms, lines like:
 *   <<CAL|ADD|Title|YYYY-MM-DD|HH:MM|60>>
 *   <<CAL|DEL|Title|YYYY-MM-DD|HH:MM>>
 * A reschedule is a DEL for the old time plus an ADD for the new time.
 * Returns the reply with all markers stripped, plus the parsed actions.
 */
object CalendarActions {

    private val REGEX = Regex("""<<CAL\|([^>]*)>>""")

    fun parse(reply: String): Pair<String, List<CalAction>> {
        val actions = mutableListOf<CalAction>()
        var clean = reply
        for (match in REGEX.findAll(reply)) {
            clean = clean.replace(match.value, "")
            val parts = match.groupValues[1].split("|").map { it.trim() }
            if (parts.isEmpty()) continue
            when (parts[0].uppercase()) {
                "ADD" -> if (parts.size >= 4) {
                    val title = parts[1].ifBlank { "Event" }
                    val duration = parts.getOrNull(4)?.toIntOrNull() ?: 60
                    val start = parseStart(parts[2], parts[3])
                    if (start != null) actions.add(CalAction.Add(title, start, duration))
                }
                "DEL", "DELETE", "REMOVE" -> if (parts.size >= 3) {
                    actions.add(CalAction.Delete(parts[1], parts[2], parts.getOrNull(3) ?: ""))
                }
            }
        }
        return clean.trim() to actions
    }

    fun parseStart(date: String, time: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$date $time")?.time
    } catch (e: Exception) {
        null
    }
}
