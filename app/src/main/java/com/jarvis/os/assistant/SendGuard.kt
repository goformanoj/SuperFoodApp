package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenStep

/**
 * "Type it" and "send it" are different instructions, and only one of them is
 * reversible. Sending a message to another person cannot be undone, so when the
 * user asks only to compose, a trailing submit is dropped even if the model
 * added one out of habit — its search examples all end in a submit, so it tends
 * to treat typing and sending as a single move.
 *
 * Deliberately conservative: it only intervenes when the user clearly said
 * compose AND clearly did not say send. Anything ambiguous is left alone, so
 * this cannot silently break "search for X" or "type X and send it".
 */
object SendGuard {

    // "put" is left out on purpose ("put on some music" is not composing), and so
    // is "reply" — "draft a reply" composes while "reply saying yes" sends, so it
    // cannot be classified either way from the verb alone.
    /** Verbs that mean "place this text there", with no instruction to submit it. */
    private val COMPOSE = listOf("type", "write", "draft", "compose")

    /** Verbs that authorise the irreversible part. */
    private val SEND = listOf("send", "post", "deliver", "fire it off", "shoot it")

    /** Submitting a search is not sending a message — never suppress these. */
    private val SUBMIT = listOf("search", "look up", "google", "find", "play", "go ahead", "enter", "submit")

    /** A tap that would perform the send itself, rather than move around the app. */
    private val SEND_CONTROL = listOf("send", "send message", "send now")

    /**
     * True when the user asked to compose text but never authorised sending it.
     */
    fun composeOnly(utterance: String): Boolean {
        val text = normalise(utterance)
        // Un-negated matches only: "write it but don't send it" must NOT count as a
        // send, or the trailing send survives and the message goes out (see Negation).
        val asksCompose = COMPOSE.any { Negation.hasUnnegated(text, it) }
        val asksSend = SEND.any { Negation.hasUnnegated(text, it) }
        val asksSubmit = SUBMIT.any { Negation.hasUnnegated(text, it) }
        return asksCompose && !asksSend && !asksSubmit
    }

    /**
     * Drops a trailing submit/send from [steps] when [utterance] only asked for
     * typing. Only trailing steps are removed — a submit in the middle of a
     * sequence is part of getting somewhere (searching, then acting on a result)
     * and is left intact.
     */
    fun apply(utterance: String, steps: List<ScreenStep>): List<ScreenStep> {
        if (!composeOnly(utterance)) return steps
        var end = steps.size
        while (end > 0 && isSubmitting(steps[end - 1])) end--
        return if (end == steps.size) steps else steps.subList(0, end).toList()
    }

    private fun isSubmitting(step: ScreenStep): Boolean = when (step) {
        is ScreenStep.Enter -> true
        is ScreenStep.Tap -> SEND_CONTROL.contains(normalise(step.label))
        else -> false
    }

    private fun normalise(text: String): String = text.lowercase()
        .replace("'", "")
        .replace("’", "")
        .replace(Regex("[^a-z ]"), " ")
        .replace(Regex(" +"), " ")
        .trim()
}
