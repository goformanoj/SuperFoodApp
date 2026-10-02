package com.jarvis.os.desktop

/**
 * How JARVIS talks on screen, in one place, in every theme: the terse, instrument-panel register of a
 * system reporting its own state ("SYSTEMS NOMINAL · AWAITING COMMAND"), not a chatbot's cheerful
 * "How can I help you today?".
 *
 * Pure, so the wording is tested. The one rule it must keep, from Telemetry: nothing here may be
 * decorative DATA. The log lines are built only from values the app really has, so a line like
 * "link.latency ... 142 ms" is true when it is shown; with no value yet it says so instead of inventing one.
 */
object JarvisVoice {

    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    const val TAGLINE = "All systems nominal. Awaiting your command."
    const val COMPOSER_HINT = "Enter a command or ask a question…  (Ctrl+Space for voice)"

    /** The state of the assistant, as the status line reports it. */
    enum class Status(val line: String) {
        Offline("Uplink offline"),
        Ready("Systems nominal"),
        Listening("Audio input active"),
        Transcribing("Decoding audio"),
        Thinking("Processing"),
        Speaking("Transmitting · click the mic to interrupt"),
        Fault("Fault detected"),
    }

    /** The three quick commands on Home: what the chip says, and the text it starts typing for you. */
    data class Quick(val label: String, val prompt: String)

    val QUICK = listOf(
        Quick("Run day plan", "Plan my day around my priorities: "),
        Quick("Brief me simply", "Explain this in plain words: "),
        Quick("Log to memory", "Remember that "),
    )

    /** Everything a log line can be built from. Null means "not known yet" and is shown as such. */
    data class Snapshot(
        val uplink: Boolean,
        val node: String,
        val latencyMs: Int?,
        val plan: String,
        val facts: Int,
        val conversations: Int,
        val cpuPercent: Int?,
        val memUsedGb: Double?,
        val memTotalGb: Double?,
        val uptime: String,
    )

    /**
     * The system log: one line per real fact, dotted into aligned columns the way a boot log is.
     * The lines are always the same set in the same order; only the values change.
     */
    fun logLines(s: Snapshot): List<String> {
        fun row(key: String, value: String) = "> " + key + ".".repeat((16 - key.length).coerceAtLeast(2)) + " " + value
        return listOf(
            row("link.uplink", if (s.uplink) "ESTABLISHED  node=${s.node}" else "DOWN"),
            row("link.latency", s.latencyMs?.let { "$it ms" } ?: "awaiting first reply"),
            row("link.plan", s.plan.uppercase()),
            row("memory.core", "${s.facts} fact${if (s.facts == 1) "" else "s"} indexed"),
            row("sessions", "${s.conversations} open"),
            row("sys.cpu", s.cpuPercent?.let { "$it%" } ?: "sampling"),
            row("sys.memory", if (s.memUsedGb != null && s.memTotalGb != null) "%.1f / %.1f GB".format(java.util.Locale.US, s.memUsedGb, s.memTotalGb) else "sampling"),
            row("sys.uptime", s.uptime),
        )
    }
}
