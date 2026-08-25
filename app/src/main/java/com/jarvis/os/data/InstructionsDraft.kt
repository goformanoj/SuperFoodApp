package com.jarvis.os.data

/**
 * Custom instructions as *answers to questions*, rather than as a blank box.
 *
 * ## Why the free-text box was the wrong shape
 *
 * The screen asked for "custom instructions" and gave a 1000-character empty
 * field. That puts the whole burden on the user: they have to invent what an
 * instruction even is, guess what phrasing works, and know what is worth saying.
 * Underneath it sat a list of pre-written sentences to append, which is the
 * tell — the design already knew people would not know what to write, and
 * answered that with a menu of sentences to paste into a textarea.
 *
 * Almost everything people actually want here is the same three or four things:
 * what to call me, how long answers should be, how formal to be. Those are
 * **questions with answers**, not prose. Asking them directly is faster, gives a
 * result on the first tap, and cannot be got wrong.
 *
 * ## Why it still stores one string
 *
 * The model is sent one block of text, and this composes into exactly that. No
 * migration, no new preference keys, and anything typed before this existed
 * survives untouched — it simply lands in [extra].
 *
 * [compose] and [parse] round-trip for every line this file generates, which is
 * what lets the screen show a stored preference back as a selected chip instead
 * of as a sentence the user has to recognise.
 */
data class InstructionsDraft(
    /** What JARVIS should call them. Empty for "don't". */
    val callMe: String = "",
    val length: AnswerLength? = null,
    val tone: Tone? = null,
    /** Everything this file did not write — including anything typed by hand. */
    val extra: String = "",
)

enum class AnswerLength(val line: String, val label: String) {
    Brief("Keep answers to one sentence unless I ask for detail.", "Brief"),
    Full("Give me the full detail, I do not mind long answers.", "Detailed"),
}

enum class Tone(val line: String, val label: String) {
    Casual("Talk to me like a buddy.", "Casual"),
    Formal("Keep a formal, professional tone.", "Formal"),
}

object Instructions {

    /** The prefix of the naming line, so [parse] can recover the name from it. */
    private const val CALL_ME = "Call me "

    /**
     * The draft as the single block of text the model is sent.
     *
     * Order is fixed rather than however the user happened to fill the form in:
     * the same choices must always produce the same string, or a save with no
     * edits would look like a change.
     */
    fun compose(draft: InstructionsDraft): String {
        val lines = mutableListOf<String>()
        val name = draft.callMe.trim()
        if (name.isNotEmpty()) lines += "$CALL_ME$name."
        draft.length?.let { lines += it.line }
        draft.tone?.let { lines += it.line }
        val rest = draft.extra.trim()
        if (rest.isNotEmpty()) lines += rest
        return lines.joinToString("\n")
    }

    /**
     * A stored block back into a draft.
     *
     * Only lines this file writes are claimed; **everything else is preserved
     * verbatim** in [InstructionsDraft.extra]. That matters more than the
     * recognition does — a user who typed three careful sentences before this
     * screen changed must not lose them because a parser did not understand them.
     */
    fun parse(stored: String): InstructionsDraft {
        var callMe = ""
        var length: AnswerLength? = null
        var tone: Tone? = null
        val rest = mutableListOf<String>()

        stored.split("\n").forEach { raw ->
            val line = raw.trim()
            when {
                // Kept, not skipped. A blank line inside someone's own text is
                // their paragraphing; the trailing `.trim()` removes the ones at
                // the ends, which are the only ones this parser created.
                line.isEmpty() -> rest += raw
                AnswerLength.entries.any { it.line == line } ->
                    length = AnswerLength.entries.first { it.line == line }
                Tone.entries.any { it.line == line } ->
                    tone = Tone.entries.first { it.line == line }
                callMe.isEmpty() && line.startsWith(CALL_ME) && line.endsWith(".") ->
                    callMe = line.removePrefix(CALL_ME).dropLast(1).trim()
                else -> rest += raw
            }
        }
        return InstructionsDraft(
            callMe = callMe,
            length = length,
            tone = tone,
            // Trimmed at the ends only: interior blank lines are the user's own
            // paragraphing and are not this parser's to tidy.
            extra = rest.joinToString("\n").trim(),
        )
    }
}
