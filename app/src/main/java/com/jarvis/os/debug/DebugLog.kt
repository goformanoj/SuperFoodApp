package com.jarvis.os.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * An in-memory trace of what JARVIS actually did, so a failure can be read
 * instead of guessed at. Every turn records what was heard, what the model
 * replied, which markers were parsed, which actions ran, and what was spoken.
 * The user exports it from the Diagnostics screen.
 *
 * Deliberately memory-only (nothing written to disk) and capped at
 * [MAX_ENTRIES], and every entry is passed through [redact] so an API key can
 * never travel out in a shared log.
 */
object DebugLog {

    const val MAX_ENTRIES = 300

    enum class Stage(val label: String) {
        HEARD("HEARD"),
        THINK("THINK"),
        REPLY("REPLY"),
        MARKERS("MARKS"),
        CALENDAR("CAL"),
        SCREEN("SCREEN"),
        SPOKE("SPOKE"),
        SESSION("SESS"),
        ERROR("ERROR"),
    }

    data class Entry(val timeMillis: Long, val stage: Stage, val detail: String)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun log(stage: Stage, detail: String) {
        entries.addLast(Entry(System.currentTimeMillis(), stage, redact(detail)))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    /**
     * Strips anything shaped like an API key. The log is meant to be shared, and
     * a stack trace or an error body from a provider can echo the key back.
     */
    fun redact(text: String): String = text
        .replace(GROQ_KEY, REDACTED)
        .replace(GOOGLE_KEY, REDACTED)
        .replace(BEARER, "Bearer $REDACTED")

    /** The whole log as shareable plain text, newest last. */
    fun export(header: String = ""): String {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        val body = snapshot().joinToString("\n") { entry ->
            "${clock.format(Date(entry.timeMillis))}  ${entry.stage.label.padEnd(6)}  ${entry.detail}"
        }
        return if (header.isBlank()) body else "$header\n\n$body"
    }

    private const val REDACTED = "***redacted***"
    private val GROQ_KEY = Regex("""gsk_[A-Za-z0-9]{8,}""")
    private val GOOGLE_KEY = Regex("""AIza[A-Za-z0-9_\-]{20,}""")
    private val BEARER = Regex("""Bearer\s+[A-Za-z0-9_\-.]{12,}""")
}
