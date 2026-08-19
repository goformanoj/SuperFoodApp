package com.jarvis.os.assistant

/**
 * "Do it." — deciding whether a reply is a yes, a no, or something else
 * entirely, so a confirmed action can run without asking the model again.
 *
 * ## Why this cannot be the model's job
 *
 * From a device trace: the user asked JARVIS to write a message and send it. He
 * typed a good one, could not find the send control, and asked *"I'm about to
 * tap Send, which I can't undo. Shall I?"*. The user said **"do it"** — and the
 * reply went back to the model, which produced a **different message**, typed
 * that instead, and sent it. The user confirmed one thing; another thing
 * happened, and messages do not come back.
 *
 * The lesson is [SendGuard]'s, exactly: an irreversible action gets decided in
 * code. A confirmation is the moment the user hands over authority for one
 * specific act, so that act has to be the one that runs — not a fresh plan for
 * the same goal, however reasonable the fresh plan looks.
 *
 * Pure, so it is unit-tested rather than reasoned about.
 */
object Confirmation {

    /** Plain agreement, and nothing else. */
    private val YES = setOf(
        "yes", "yeah", "yep", "yup", "ya", "sure", "ok", "okay", "okey",
        "do it", "go ahead", "go on", "please do", "yes please", "send it",
        "confirm", "confirmed", "affirmative", "correct", "right", "carry on",
        "continue", "proceed", "of course", "definitely", "absolutely",
        "do that", "yes do it", "go for it", "hit it", "send", "yes send it",
    )

    /** Plain refusal. */
    private val NO = setOf(
        "no", "nope", "nah", "dont", "do not", "stop", "cancel", "abort",
        "no thanks", "no thank you", "leave it", "forget it", "never mind",
        "nevermind", "not now", "dont do it", "do not do it", "dont send",
        "do not send", "dont send it", "wait", "hold on", "no dont",
    )

    /**
     * True when [text] is nothing but agreement.
     *
     * Whole-utterance matching on purpose. "yes but change the wording first"
     * contains "yes" and is emphatically NOT permission to run the stored step —
     * treating it as one would send the wrong message, which is the exact bug
     * this exists to prevent. Anything that is not plainly a yes falls through to
     * the model, where it becomes an ordinary request.
     */
    fun isYes(text: String): Boolean = normalise(text) in YES

    /** True when [text] is nothing but refusal. */
    fun isNo(text: String): Boolean = normalise(text) in NO

    /**
     * What the answer means for a step that is waiting.
     *
     * Deliberately three-valued. "Neither" is the common case and must not be
     * guessed at in either direction: read as yes it fires an irreversible
     * action nobody authorised, read as no it silently drops something the user
     * asked for.
     */
    enum class Answer { YES, NO, NEITHER }

    fun answerFor(text: String): Answer = when {
        isYes(text) -> Answer.YES
        isNo(text) -> Answer.NO
        else -> Answer.NEITHER
    }

    private fun normalise(text: String): String =
        text.lowercase()
            .replace(APOSTROPHE, "")
            .replace(NON_WORD, " ")
            .trim()
            .replace(SPACES, " ")

    private val APOSTROPHE = Regex("[’']")
    private val NON_WORD = Regex("[^a-z0-9 ]")
    private val SPACES = Regex(" {2,}")
}
