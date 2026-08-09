package com.jarvis.os.assistant

/**
 * Whole-word trigger matching that a negation turns OFF.
 *
 * The safety guards ([SendGuard], [SpendGuard], [AlarmGuard]) decide what to allow
 * by spotting a word in the user's request — "send", "checkout", "alarm". A plain
 * match reads "write it but **don't** send it" as permission to send, because the
 * word "send" is right there. That is the dangerous direction: the guard is meant to
 * PROTECT, and a negated trigger must not switch that protection off.
 *
 * So a trigger counts only when it appears with **no negator in the few words before
 * it**. Callers pass text their own `normalise` already lowercased and stripped of
 * apostrophes (so "don't" → "dont"). This only ever makes a guard more cautious.
 */
internal object Negation {

    /** Words that cancel a trigger just after them. "no" is left out — too ambiguous. */
    private val NEGATORS = setOf(
        "not", "dont", "doesnt", "didnt", "wont", "cant", "cannot", "never", "without",
    )

    /** How many words before the trigger a negator can sit and still cancel it. */
    private const val WINDOW = 3

    /**
     * True when [trigger] (one or more whole words) occurs in [normalised] at least
     * once WITHOUT a negator in the [WINDOW] words immediately before it.
     */
    fun hasUnnegated(normalised: String, trigger: String): Boolean {
        val tokens = normalised.split(' ').filter { it.isNotEmpty() }
        val target = trigger.split(' ').filter { it.isNotEmpty() }
        if (target.isEmpty()) return false
        var i = 0
        while (i + target.size <= tokens.size) {
            if (tokens.subList(i, i + target.size) == target) {
                val from = maxOf(0, i - WINDOW)
                if ((from until i).none { tokens[it] in NEGATORS }) return true
            }
            i++
        }
        return false
    }
}
