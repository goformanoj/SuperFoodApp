package com.jarvis.os.assistant

/**
 * Asking and acting are different turns. Doing both at once is worse than doing
 * either.
 *
 * From a device trace: "play Beat It" produced *"I'll get that playing for you,
 * what app would you like to use, or should I open YouTube?"* — and then opened
 * YouTube anyway. The user's next words were "you ask me for the option but you
 * still open YouTube". The reply pretends to offer a choice while taking it, so
 * the user is answering a question that has already been decided.
 *
 * When the spoken reply ends in a question, the actions are dropped and the
 * question stands. The user answers, and the next turn acts — which is what the
 * question implied would happen.
 */
object AskGuard {

    /**
     * True when the reply's final sentence is a question to the user.
     *
     * The LAST sentence specifically. "Should I use YouTube?" at the end is an
     * open question; a rhetorical aside mid-reply ("remember that playlist you
     * asked about? here it is") is not, and neither is a reply that asks
     * something and then answers it.
     */
    fun asksQuestion(spoken: String): Boolean {
        val text = spoken.trim()
        if (text.isEmpty()) return false
        if (!text.endsWith("?")) return false
        // A question the model asks and answers itself, or a one-word confirmation
        // like "Ready?", is not a request for input that should block an action.
        return text.split(" ").count { it.isNotBlank() } >= 3
    }

    /**
     * Drops [steps] when [spoken] ends in a question.
     *
     * Everything goes, not just the tail: the trace's failure was opening YouTube
     * while asking which app to use, and keeping any prefix of that plan would
     * commit to the same choice more quietly.
     */
    fun <T> apply(spoken: String, steps: List<T>): List<T> =
        if (steps.isEmpty() || !asksQuestion(spoken)) steps else emptyList()
}
