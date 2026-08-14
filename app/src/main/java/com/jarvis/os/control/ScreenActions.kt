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

    /**
     * A clause ending in a colon, which in a spoken reply is almost always the
     * model announcing what it is about to emit — "Here are the steps:", "To do
     * that, I'll need to:". The markers are stripped, so that narration is left
     * describing nothing. Removed entirely rather than tidied, and only when the
     * reply actually carried markers, so ordinary prose is untouched.
     */
    private val NARRATION = Regex("""[^.!?]*:\s*""")

    /**
     * A sentence fragment left behind when a marker was the SUBJECT of the sentence
     * around it.
     *
     * From a device trace, the model replied:
     *   `<<TAP|Add to wishlist>> isn't the right control to add to cart, so I'll
     *   look for the right one and try again. To add brown bread…`
     * Stripping the marker left JARVIS saying "isn't the right control to add to
     * cart, so I'll look for the right one and try again." out loud — a headless
     * sentence about a control the user never heard named.
     *
     * English sentences do not begin with a lowercase word, so after stripping,
     * a reply that starts mid-word is a fragment by construction. It is dropped up
     * to and including the first sentence end. Bounded on purpose: if there is no
     * sentence end the text is left alone rather than deleted wholesale, since
     * removing the entire reply would trade a clumsy sentence for silence.
     */
    private val HEADLESS_FRAGMENT = Regex("""^[a-z][^.!?]*[.!?]+\s*""")

    // "…the steps: ." -> "…the steps."
    private val DANGLING_PUNCTUATION = Regex("""[:,]\s*(?=[.!?])""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([.!?,])""")
    private val DOUBLED_PUNCTUATION = Regex("""([.!?])\s*\1+""")
    private val TRAILING_COLON = Regex("""[:,]\s*$""")

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
        val stripped = reply
            .replace(MARKER, "")
            .replace(MARKER_RESIDUE, "")

        // Only a marker at the very START of the reply can leave a headless
        // sentence behind. Requiring that is what keeps an ordinary reply which
        // merely happens to open in lower case ("ok, opening YouTube.") intact.
        val openedWithMarker = reply.trimStart().startsWith("<<")

        val clean = stripped
            // The user should hear what JARVIS is doing, never how it plans to do
            // it. "Here are the steps: ." was genuinely spoken aloud on a device.
            .let { if (steps.isEmpty()) it else it.replace(NARRATION, "") }
            .replace(DANGLING_PUNCTUATION, "")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(DOUBLED_PUNCTUATION, "$1")
            .replace(Regex(" {2,}"), " ")
            // Markers sit on their own lines, so stripping a chain of them leaves
            // a hole. A device trace shows one reply displayed with six blank
            // lines through the middle of it. TTS ignores them, but the Chat
            // screen shows the reply exactly as it is stored.
            .replace(Regex("[ \t]*\n[ \t]*(?:\n[ \t]*)+"), "\n\n")
            .replace(TRAILING_COLON, "")
            .trim()
            .let { if (openedWithMarker) it.replace(HEADLESS_FRAGMENT, "").trim() else it }
        return Plan(clean, steps)
    }
}
