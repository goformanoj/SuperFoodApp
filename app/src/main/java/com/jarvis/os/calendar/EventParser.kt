package com.jarvis.os.calendar

import java.text.SimpleDateFormat
import java.util.Locale

data class PendingEvent(val title: String, val startMillis: Long, val durationMin: Int)

/**
 * Extracts a calendar event from an AI reply. The model emits, only after the
 * user confirms, a line like:  <<EVENT|Title|YYYY-MM-DD|HH:MM|60>>
 * Returns the reply with that marker stripped, plus the parsed event (or null).
 */
object EventParser {

    private val REGEX = Regex("""<<EVENT\|([^|]*)\|([^|]*)\|([^|]*)\|([^>]*)>>""")

    fun parse(reply: String): Pair<String, PendingEvent?> {
        val match = REGEX.find(reply) ?: return reply.trim() to null
        val clean = reply.replace(match.value, "").trim()
        val title = match.groupValues[1].trim().ifBlank { "Event" }
        val date = match.groupValues[2].trim()
        val time = match.groupValues[3].trim()
        val duration = match.groupValues[4].trim().toIntOrNull() ?: 60
        val start = parseStart(date, time) ?: return clean to null
        return clean to PendingEvent(title, start, duration)
    }

    private fun parseStart(date: String, time: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$date $time")?.time
    } catch (e: Exception) {
        null
    }
}
