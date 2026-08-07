package com.jarvis.os.voice

/**
 * Detects the "Hey JARVIS" wake phrase at the start of a speech transcript and
 * splits off any command spoken after it.
 *
 * This is what lets JARVIS stay asleep — listening, but silent and inert — until
 * it is actually called, instead of reacting to every stray sentence in the room
 * (the trace that prompted this had it talking to itself for a full minute).
 *
 * Two things make it tolerant enough to be usable from speech:
 *  - The recogniser mangles "jarvis" constantly (jervis, javis, travis, even
 *    "service"), so a set of near-homophones counts, not just the exact word.
 *  - An optional leading filler ("hey", "ok", "hi") is allowed before the name.
 *
 * It is deliberately anchored to the START (after at most one filler) rather than
 * matching the name anywhere: "thank you Jarvis" is the stop phrase, not a wake,
 * and a name buried mid-sentence is far more likely a mis-hear than a summons.
 *
 * Pure text in, pure text out — no Android — so the whole rule is unit-tested.
 */
object WakeWord {

    /** "jarvis" and the near-homophones the recogniser really returns for it. */
    private val NAMES = setOf(
        "jarvis", "jarvi", "jarvis's", "jarviss", "jervis", "javis", "javis's",
        "jarvez", "jarvace", "jarvice", "charvis", "gervais", "travis", "service",
    )

    /** Words allowed to precede the name — a summons often starts with one. */
    private val FILLERS = setOf("hey", "hay", "hi", "hello", "ok", "okay", "yo", "oh", "a")

    /** The result of looking for the wake word: whether it fired, and the trailing command. */
    data class Wake(val matched: Boolean, val command: String)

    /**
     * Look for the wake word at the start of [text]. On a match, [Wake.command] is
     * whatever was said after it (empty for a bare "Hey JARVIS").
     */
    fun detect(text: String): Wake {
        val tokens = normalise(text).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return NO_MATCH

        // Allow one optional filler before the name ("hey jarvis", "ok jarvis").
        val nameAt = when {
            tokens[0] in NAMES -> 0
            tokens.size > 1 && tokens[0] in FILLERS && tokens[1] in NAMES -> 1
            else -> return NO_MATCH
        }
        val command = tokens.drop(nameAt + 1).joinToString(" ").trim()
        return Wake(matched = true, command = command)
    }

    /** True when [text] begins with the wake word. */
    fun isWake(text: String): Boolean = detect(text).matched

    private fun normalise(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex(" +"), " ")
            .trim()

    private val NO_MATCH = Wake(matched = false, command = "")
}
