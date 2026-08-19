package com.jarvis.os.voice

/**
 * Turns a written reply into something worth *hearing*.
 *
 * ## The failure
 *
 * The model formats for a screen, because that is what models do: `**UI
 * testing**`, `* Espresso`, `# How to test`. Android's text-to-speech reads
 * every one of those characters out loud. From a device trace the user got
 * "asterisk asterisk U I testing asterisk asterisk", which they described,
 * fairly, as ruining it.
 *
 * ## Why this is a display/speech split, not a cleanup
 *
 * The markdown is not junk — on the chat screen the bold and the bullets are
 * exactly what makes a long answer readable. So nothing is stripped from what is
 * SHOWN. This runs only on the way to the speaker, which is why it lives here
 * next to [Speaker] rather than anywhere near the reply the app stores.
 *
 * Applied at `speakTurn`, the single place in the engine that speaks — the same
 * reasoning as every other guard in this project: a rule applied at one choke
 * point cannot be missed by a path somebody adds later.
 *
 * Pure, so it is unit-tested rather than reasoned about.
 */
object SpokenText {

    /**
     * The words only, with the formatting characters gone.
     *
     * Deliberately conservative about what counts as formatting. Removing a
     * character that was really part of a sentence is worse than reading one
     * stray symbol, because the first changes the meaning and the second is
     * merely ugly.
     */
    fun plain(text: String): String {
        if (text.isBlank()) return text
        var out = text

        // Fenced code blocks: the fence is never speech. The contents are left
        // alone — if the model put a command in the reply, the user asked for it.
        out = FENCE.replace(out, "")

        // `code` -> code
        out = INLINE_CODE.replace(out, "$1")

        // [label](https://…) -> label. The URL is unspeakable either way, and
        // hearing a link read character by character is worse than not hearing it.
        out = LINK.replace(out, "$1")

        // **bold**, __bold__, *italic*, _italic_, ~~struck~~ -> the word itself.
        // Anchored on a non-space so "2 * 3" and a lone underscore survive.
        out = BOLD.replace(out, "$1")
        out = UNDER_BOLD.replace(out, "$1")
        out = ITALIC.replace(out, "$1")
        out = UNDER_ITALIC.replace(out, "$1")
        out = STRIKE.replace(out, "$1")

        // Leading list markers and heading hashes, per line. The NUMBER in "1."
        // stays — a spoken list that counts is easier to follow, not harder.
        out = out.lines().joinToString("\n") { line ->
            line.replace(LEADING_BULLET, "").replace(LEADING_HEADING, "")
        }

        // Horizontal rules read as a run of dashes.
        out = RULE.replace(out, "")

        // Anything left over: a stray marker the patterns above did not pair up.
        // Reached only when the model emitted unbalanced formatting, which it does.
        out = LONE_MARK.replace(out, "")

        // Formatting removal leaves holes. Collapse them, but keep paragraph
        // breaks — [Speaker] pauses at them, and that pause is the difference
        // between a list and a run-on sentence.
        out = out.replace(SPACES, " ")
        out = out.replace(BLANK_LINES, "\n\n")
        return out.trim()
    }

    private val FENCE = Regex("""```[a-zA-Z0-9+#-]*\n?""")
    private val INLINE_CODE = Regex("""`([^`\n]+)`""")
    private val LINK = Regex("""\[([^\]\n]+)]\((?:[^)\s]+)\)""")
    private val BOLD = Regex("""\*\*(\S(?:[^*]*\S)?)\*\*""")
    private val UNDER_BOLD = Regex("""__(\S(?:[^_]*\S)?)__""")
    private val ITALIC = Regex("""\*(\S(?:[^*\n]*\S)?)\*""")
    private val UNDER_ITALIC = Regex("""(?<![A-Za-z0-9])_(\S(?:[^_\n]*\S)?)_(?![A-Za-z0-9])""")
    private val STRIKE = Regex("""~~(\S(?:[^~]*\S)?)~~""")

    /** `- `, `* `, `+ ` and `• ` at the start of a line, with any indent. */
    private val LEADING_BULLET = Regex("""^\s*[-*+•]\s+""")

    /** `#`, `##`, … at the start of a line. */
    private val LEADING_HEADING = Regex("""^\s*#{1,6}\s+""")

    /** `---`, `***`, `___` alone on a line. */
    private val RULE = Regex("""(?m)^\s*([-*_])\1{2,}\s*$""")

    /** A `*` or `` ` `` with no partner left. */
    private val LONE_MARK = Regex("""[*`]""")

    private val SPACES = Regex("""[ \t]{2,}""")
    private val BLANK_LINES = Regex("""\n{3,}""")
}
