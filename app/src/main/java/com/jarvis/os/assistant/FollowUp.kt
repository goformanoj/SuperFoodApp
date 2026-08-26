package com.jarvis.os.assistant

/**
 * Finds the question hiding inside a command that also asks for an action.
 *
 * WHY THIS EXISTS
 * ---------------
 * From a device trace (2026-08-26, 16:07):
 *
 * ```
 * 16:07:52  HEARD   (voice) can you open the pw app and tell me if i have any classes today
 * 16:07:55  REPLY   Groq/FAST: <<OPEN|pw>>
 * 16:07:56  SCREEN  running Open(app=pw)
 * ...silence...
 * 16:08:21  HEARD   (voice) do i have classes today
 * 16:08:24  REPLY   Groq/SMART: Opening the weekly schedule now.<<TAP|View Weekly Schedule>>
 * 16:08:28  SCREEN  step 1/1 Tap(label=View Weekly Schedule)
 * ...silence...
 * 16:08:38  HEARD   (voice) you didn't reply to me
 * 16:08:38  REPLY   Groq/SMART: There are no classes scheduled for today.
 * ```
 *
 * The app did everything asked of it except the part the user cared about. The
 * request carried TWO things — open the app, and answer a question — and the
 * screen action consumed the whole turn. Nothing in the engine ever looked at
 * the screen the action had produced, so the answer only arrived when the user
 * complained, from a screen that had been sitting there for ten seconds.
 *
 * This is the pure half of the fix: given what the user said, return the
 * question that will still be outstanding once the action has run, or null when
 * there isn't one. The engine holds it, runs the action, then answers it from
 * the screen the action landed on.
 *
 * The bar for returning non-null is deliberately high. A false positive means
 * JARVIS says a second, unasked-for sentence after every command — worse than
 * the silence it replaces, because it happens on every single turn.
 */
object FollowUp {

    /**
     * Splitting on these gives the parts of a compound request. `,` is included
     * because the trace's own phrasing works both ways ("open pw, do i have
     * classes today") and speech-to-text punctuates unpredictably.
     */
    private val SPLIT = Regex("""\s*(?:,|;|\band\b|\bthen\b|&)\s*""", RegexOption.IGNORE_CASE)

    /**
     * Verbs that make a segment an ACTION rather than a question. Checked FIRST,
     * because "can you open the pw app" opens with a question word and is not a
     * question — the model answers it by opening the app, not by talking.
     */
    private val ACTION_VERBS = setOf(
        "open", "launch", "start", "run", "close", "quit",
        "send", "text", "message", "whatsapp", "email", "reply", "forward",
        "type", "write", "tap", "click", "press", "select", "scroll", "swipe",
        "play", "pause", "resume", "skip", "stop",
        "set", "add", "delete", "remove", "cancel", "schedule", "remind",
        "call", "dial", "ring",
        "search", "google", "look", "find", "order", "buy", "book", "pay",
        "go", "navigate", "take", "turn", "switch", "make", "create", "download",
    )

    /**
     * Ways of asking for something to be REPORTED BACK. Only first-person forms
     * count: "tell me the last message" is a question, "tell her I'll be late"
     * is an errand, and confusing the two would have JARVIS answering a message
     * it was supposed to send.
     */
    private val REPORT_PHRASES = listOf(
        "tell me", "let me know", "show me", "read me", "read out", "say out",
        "check if", "check whether", "check what", "check when",
        "see if", "see whether", "find out", "let me hear",
    )

    /** A segment opening with one of these, and no action verb in it, is a question. */
    private val WH_STARTS = setOf(
        "what", "whats", "when", "where", "who", "whos", "why", "which", "how", "whose",
    )

    /**
     * Auxiliaries only open a question when the sentence is about the user —
     * "do i have classes today" is a question, "have a look" is not. The
     * pronoun is what separates them, and without that test the second one
     * would have JARVIS answering a phrase nobody asked.
     */
    private val AUX_STARTS = setOf(
        "is", "are", "was", "were", "am", "do", "does", "did",
        "can", "could", "will", "would", "should", "has", "have", "had",
    )

    private val FIRST_PERSON = setOf("i", "im", "i'm", "me", "my", "mine", "we", "our", "us")

    /** Filler that would otherwise ride at the front of the returned question. */
    private val LEAD_FILLER = setOf("and", "then", "also", "please", "plus", "so", "now", "just")

    private enum class Kind { ACTION, QUESTION, OTHER }

    /**
     * The question left over once the action in [utterance] has been carried
     * out, or null if the utterance is only an action, only a question, or
     * neither.
     *
     * Only a compound returns non-null. A bare question ("do i have classes
     * today") is answered by the normal reply path and must NOT come back
     * here — answering it twice is the same bug in the other direction.
     */
    fun questionIn(utterance: String): String? {
        val trimmed = utterance.trim()
        val parts = SPLIT.split(trimmed)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val kinds = parts.map { kindOf(it.trim('.', '?', '!')) }
        if (Kind.ACTION !in kinds) return null
        // The question must come AFTER an action, which is the shape the trace
        // had. A question first ("what's the score, then open youtube") is
        // answered by the ordinary reply path before the action ever runs.
        val firstAction = kinds.indexOf(Kind.ACTION)
        val at = (firstAction + 1 until parts.size).firstOrNull { kinds[it] == Kind.QUESTION }
            ?: return null

        // Everything from the question onwards, taken from the ORIGINAL text so
        // the user's own connectives survive: "tell me if i have classes and
        // when the first one starts" is one question, not two. Unless an action
        // follows it, in which case only the question segment itself is kept —
        // re-running an action that has already run is the worse mistake.
        val tailIsClean = (at until parts.size).none { kinds[it] == Kind.ACTION }
        val question = if (tailIsClean) {
            val start = trimmed.indexOf(parts[at])
            if (start >= 0) trimmed.substring(start) else parts[at]
        } else {
            parts[at]
        }
        val cleaned = stripFiller(question).trim()
        // Three words is the floor. "tell me" on its own is a whole report
        // phrase and still says nothing about WHAT to report; a model handed
        // that will answer something, and whatever it answers was not asked.
        return if (cleaned.split(' ').count { it.isNotBlank() } >= 3) cleaned else null
    }

    /**
     * The question to answer once the action has run, for an utterance that the
     * model answered with screen actions.
     *
     * Two shapes reach here, and the trace has both:
     *
     * - a compound — "open the pw app AND tell me if i have any classes today",
     *   where only the tail is still outstanding once the app is open;
     * - a bare question the model chose to answer by acting — "do i have
     *   classes today", answered with a tap on "View Weekly Schedule" and then
     *   nothing at all. The whole utterance is the outstanding question there.
     *
     * A plain command ("open whatsapp") returns null: there is nothing to
     * answer, and speaking after it would add a sentence to every command.
     */
    fun pendingFor(utterance: String): String? {
        questionIn(utterance)?.let { return it }
        val whole = utterance.trim().trim('.', '?', '!')
        if (whole.isBlank()) return null
        if (kindOf(whole) != Kind.QUESTION) return null
        val cleaned = stripFiller(utterance.trim()).trim()
        return if (cleaned.split(' ').count { it.isNotBlank() } >= 3) cleaned else null
    }

    private fun kindOf(part: String): Kind {
        val words = words(part)
        if (words.isEmpty()) return Kind.OTHER
        val lower = part.lowercase()
        // Report phrases are checked BEFORE the verbs because two of them
        // collide: "find out when it starts" is a question whose first word is
        // in ACTION_VERBS. Every phrase in that list is unambiguously a request
        // to be told something, which is what earns it the first look.
        if (REPORT_PHRASES.any { it in lower }) return Kind.QUESTION
        // Then actions, deliberately ahead of the question words: nearly every
        // spoken command is dressed as a question ("can you open…",
        // "could you play…") and is answered by acting, not by talking.
        if (words.any { it in ACTION_VERBS }) return Kind.ACTION
        if (words.first() in WH_STARTS) return Kind.QUESTION
        if (words.first() in AUX_STARTS && words.any { it in FIRST_PERSON }) return Kind.QUESTION
        return Kind.OTHER
    }

    private fun stripFiller(part: String): String {
        var tokens = part.split(' ').filter { it.isNotBlank() }
        while (tokens.isNotEmpty() && tokens.first().lowercase().trim('.', ',', '?', '!') in LEAD_FILLER) {
            tokens = tokens.drop(1)
        }
        return tokens.joinToString(" ")
    }

    private fun words(part: String): List<String> =
        part.lowercase()
            .split(Regex("""[^a-z0-9']+"""))
            .filter { it.isNotBlank() }
            .map { it.trim('\'') }
            .filter { it.isNotBlank() }
}
