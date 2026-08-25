package com.jarvis.os.assistant

/**
 * The last thing between the model's reply and the user.
 *
 * ## What this catches
 *
 * Every marker the app understands is removed by the parser that claims it —
 * `ScreenActions`, `ArtifactActions`, `MemoryActions`, `AlarmActions`. That works
 * for markers the app knows about, and only for those. Nothing removes:
 *
 * - a marker the model **invented** (`<<SEARCH|…>>`, `<<THINK|…>>`);
 * - one it **misspelled** (`<<TAPP|…>>`, `<<OPEN FILE|…>>`);
 * - one **cut off** by a token limit (`<<FILE|pdf|Notes` with no closing);
 * - one with the **wrong number of fields**, which a strict regex will not match.
 *
 * Any of those survives every parser and reaches the user — printed on the chat
 * screen and, worse, **read aloud**. Hearing "less than less than tap pipe send"
 * is the assistant showing its working, and the user's instruction was plain:
 * never speak a marker, never reveal what it is thinking.
 *
 * ## Why it is a separate sweep rather than a stricter parser
 *
 * The parsers have to be strict — a loose one would claim text that merely looks
 * like a marker and silently drop real words from a reply. So they stay strict,
 * and this runs after them as a net: by the time it sees the text, anything still
 * wearing `<<…>>` is by definition something no parser wanted.
 *
 * Applied at the single point where the final reply is settled, so it covers what
 * is displayed AND what is spoken — the same choke-point reasoning as every other
 * guard here.
 */
object Markers {

    /**
     * A complete marker: `<<` … `>>`, with any number of closing brackets, and
     * not allowed to run across a line break so a stray `<<` cannot eat a
     * paragraph.
     */
    private val CLOSED = Regex("""<<[^\n<>]*>+""")

    /**
     * An unterminated one, to the end of its line.
     *
     * This is the token-limit case: the model ran out of room mid-marker. What is
     * left is never a sentence, and reading it out is the worst version of the
     * bug because it is also the longest.
     */
    private val OPEN = Regex("""<<[^\n]*$""", RegexOption.MULTILINE)

    /** Left behind when a marker is removed from the middle of a line. */
    private val DOUBLE_SPACE = Regex("[ \\t]{2,}")
    private val BLANK_RUN = Regex("\n{3,}")

    /**
     * The reply with every remaining marker removed.
     *
     * Order matters: closed markers first, so an unterminated sweep cannot eat a
     * well-formed one that happens to sit later on the same line.
     */
    fun strip(text: String): String {
        if (!text.contains("<<")) return text
        var out = CLOSED.replace(text, "")
        out = OPEN.replace(out, "")
        out = out.lines().joinToString("\n") { it.replace(DOUBLE_SPACE, " ").trimEnd() }
        out = BLANK_RUN.replace(out, "\n\n")
        return out.trim()
    }
}
