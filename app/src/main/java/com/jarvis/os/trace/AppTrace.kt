package com.jarvis.os.trace

import com.jarvis.os.debug.DebugLog

/**
 * A structured, shareable record of what JARVIS did — the next step past the flat
 * text log (Part C2.1). Where [DebugLog.export] is a stream of timestamped lines for
 * a human to read, this segments that same stream into **turns** (one per thing the
 * user asked) each carrying its ordered **steps** and an inferred **outcome**. That
 * shape is what a later stage can bake into a server-side app pack (C2.2/C2.3): "on
 * Blinkit, this goal, these screens, it got stuck here".
 *
 * ## Privacy (non-negotiable, see PRODUCT_PLAN Part C2)
 *
 * A trace is meant to leave the device, so it must not carry secrets. Two layers:
 * the entries are already key-redacted (every `DebugLog.log` runs [DebugLog.redact]
 * before storing), and here every field is additionally scrubbed of OTP/PIN/card-like
 * digit runs — the same `\d{4,8}` shape [com.jarvis.os.control.ScreenMatch] redacts
 * on screen. Duplicated deliberately as a single self-contained pure module: a
 * shareable artifact should carry its own guarantee, not borrow one across a package.
 *
 * Pure and unit-tested; the JSON is hand-rolled (no `org.json`) so the whole thing
 * verifies off-device.
 */
object AppTrace {

    const val SCHEMA = "jarvis.app-trace/1"

    data class Step(val stage: String, val detail: String)

    data class Turn(val goal: String, val outcome: String, val steps: List<Step>)

    /** A turn's outcome, inferred from its steps. */
    object Outcome {
        const val OK = "ok"
        const val STUCK = "stuck"
        const val ASKED = "asked"
        const val FAILED = "failed"
        const val UNKNOWN = "unknown"
    }

    /**
     * Segments a chronological entry list (oldest first, as [DebugLog.snapshot]
     * returns it) into turns: a new turn begins at each `HEARD`, and everything up
     * to the next `HEARD` is its steps. Entries before the first `HEARD` are dropped
     * — a turn with no goal is not a trace of anything.
     */
    fun build(entries: List<DebugLog.Entry>): List<Turn> {
        val turns = mutableListOf<Turn>()
        var goal: String? = null
        var steps = mutableListOf<Step>()

        fun flush() {
            val g = goal ?: return
            turns.add(Turn(g, outcomeOf(steps), steps.toList()))
        }

        for (e in entries) {
            if (e.stage == DebugLog.Stage.HEARD) {
                flush()
                goal = scrub(e.detail)
                steps = mutableListOf()
            } else if (goal != null) {
                steps.add(Step(e.stage.label, scrub(e.detail)))
            }
        }
        flush()
        return turns
    }

    /** Builds the turns from the current log and serialises them as JSON. */
    fun exportJson(entries: List<DebugLog.Entry>, createdAt: Long): String =
        toJson(build(entries), createdAt)

    fun toJson(turns: List<Turn>, createdAt: Long): String {
        val sb = StringBuilder()
        sb.append("{\"schema\":").append(jsonString(SCHEMA))
        sb.append(",\"createdAt\":").append(createdAt)
        sb.append(",\"turns\":[")
        turns.forEachIndexed { i, turn ->
            if (i > 0) sb.append(',')
            sb.append("{\"goal\":").append(jsonString(turn.goal))
            sb.append(",\"outcome\":").append(jsonString(turn.outcome))
            sb.append(",\"steps\":[")
            turn.steps.forEachIndexed { j, step ->
                if (j > 0) sb.append(',')
                sb.append("{\"stage\":").append(jsonString(step.stage))
                sb.append(",\"detail\":").append(jsonString(step.detail))
                sb.append('}')
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * The outcome of a turn from its steps. A definite signal wins over silence, and
     * an explicit "met"/"stuck"/"asked" note from the agent loop wins over a bare
     * error line (the loop reports "stuck" precisely when it also logged errors on
     * the way). Falls back to "failed" for a turn that only errored, else "unknown".
     */
    private fun outcomeOf(steps: List<Step>): String {
        var sawError = false
        for (s in steps) {
            val d = s.detail
            if (d.contains("goal is met")) return Outcome.OK
            if (d.contains("is stuck")) return Outcome.STUCK
            if (d.contains("stopped to ask")) return Outcome.ASKED
            if (s.stage == DebugLog.Stage.ERROR.label) sawError = true
        }
        return if (sawError) Outcome.FAILED else Outcome.UNKNOWN
    }

    /** Removes OTP/PIN/card-like digit runs before the trace can be shared. */
    private fun scrub(text: String): String = text.replace(DIGIT_RUN, "***")

    // Broader than ScreenMatch.OTP_LIKE (`\d{4,8}`) on purpose: this artifact leaves
    // the device, so a 4+ digit run is scrubbed — which covers an OTP/PIN *and* a
    // 13-19 digit card number that the 4-8 bound would miss (no boundary inside it).
    private val DIGIT_RUN = Regex("""\b\d{4,}\b""")

    /** JSON string literal with the escaping the spec requires. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
