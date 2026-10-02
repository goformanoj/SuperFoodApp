package com.jarvis.os.agent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The grounding line sent with every tool-calling request. Without it the model has no idea what
 * "tomorrow" or "Friday" means and (found live on the emulator, 2026-10-02) answers "what's today's
 * date?" instead of adding the task. PHONE_AGENT_PROMPT tells it to read the date and the next
 * seven days from here and never to work out a weekday's date itself.
 *
 * Pure: the clock and zone are passed in, so it is unit-tested (TurnContextTest).
 */
object TurnContext {
    fun now(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.ENGLISH): String {
        val at = Instant.ofEpochMilli(nowMs).atZone(zone)
        val full = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", locale)
        val day = DateTimeFormatter.ofPattern("EEEE d MMM", locale)
        val next = (1..7).joinToString("; ") { at.plusDays(it.toLong()).format(day) }
        return "Current date/time: ${at.format(full)} (${zone.id}). The next seven days: $next."
    }
}
