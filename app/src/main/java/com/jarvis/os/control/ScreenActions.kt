package com.jarvis.os.control

/** One step in a screen-control sequence. */
sealed interface ScreenStep {
    data class Open(val app: String) : ScreenStep      // launch an app by name
    data class Tap(val label: String) : ScreenStep     // tap a control by its visible label
    data class Type(val text: String) : ScreenStep     // type into the focused field
    data object Enter : ScreenStep                      // submit / press the search or enter key

    /**
     * Look at the screen and choose what matches [description], rather than
     * guessing a label up front. "The first video result" is an intent, not a
     * label — no amount of string matching finds it, because the results do not
     * exist yet when the plan is written.
     */
    data class Pick(val description: String) : ScreenStep

    /** The system Back action — not a hunt for a control labelled "Back". */
    data object Back : ScreenStep

    /** The system Home action, for getting out of an app entirely. */
    data object Home : ScreenStep
}

/**
 * Parses screen-control command markers out of an AI reply, IN ORDER, so a single
 * instruction can be a sequence:
 *   <<OPEN|YouTube>> <<TAP|Search>> <<TYPE|standup comedy>> <<ENTER>>
 * The markers are stripped from the spoken text.
 */
object ScreenActions {

    // Tolerant of a single closing '>' on purpose. The model does emit
    // "<<TAP|Thriller by Michael Jackson>" occasionally, and a rigid parser both
    // skips the action AND leaks the raw marker into the spoken reply.
    private val MARKER =
        Regex("""<<(OPEN|TAP|TYPE|ENTER|PICK|BACK|HOME)(?:\|([^>\n]*))?>{1,2}""", RegexOption.IGNORE_CASE)

    // Safety net: anything else that still looks like a marker is stripped from
    // the spoken text even when it could not be understood as a command. Protocol
    // text must never reach the user, whatever the model produced.
    private val MARKER_RESIDUE = Regex("""<<[^<>\n]*>{0,2}""")

    data class Plan(val clean: String, val steps: List<ScreenStep>) {
        val hasAction: Boolean get() = steps.isNotEmpty()

        /** Tapping, typing and entering need the accessibility service; opening an app does not. */
        val needsAccessibility: Boolean get() = steps.any { it !is ScreenStep.Open }
    }

    fun parse(reply: String): Plan {
        val steps = mutableListOf<ScreenStep>()
        for (match in MARKER.findAll(reply)) {
            val kind = match.groupValues[1].uppercase()
            val arg = match.groupValues.getOrNull(2)?.trim().orEmpty()
            when (kind) {
                "OPEN" -> if (arg.isNotEmpty()) steps.add(ScreenStep.Open(arg))
                "TAP" -> if (arg.isNotEmpty()) steps.add(ScreenStep.Tap(arg))
                "TYPE" -> if (arg.isNotEmpty()) steps.add(ScreenStep.Type(arg))
                "ENTER" -> steps.add(ScreenStep.Enter)
                "PICK" -> if (arg.isNotEmpty()) steps.add(ScreenStep.Pick(arg))
                "BACK" -> steps.add(ScreenStep.Back)
                "HOME" -> steps.add(ScreenStep.Home)
            }
        }
        val clean = reply
            .replace(MARKER, "")
            .replace(MARKER_RESIDUE, "")
            .replace(Regex(" {2,}"), " ")
            .trim()
        return Plan(clean, steps)
    }
}
