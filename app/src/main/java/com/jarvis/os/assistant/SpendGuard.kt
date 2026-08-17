package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenStep

/**
 * A one-shot plan must not commit an irreversible action the user did not ask for.
 *
 * From a device trace: "can you go to blinkit and add some bread" produced a plan
 * ending in Tap(Checkout). Nobody asked for a checkout; it only failed to run
 * because an earlier step broke. This guard withholds that commit.
 *
 * The rule is **per action**: an irreversible tap is withheld unless the user's own
 * words named that action (un-negated). Spending needs a spend word; a call/share/
 * delete needs its verb. This does two things at once —
 *  - it still catches a checkout/pay/delete the model reached for on its own, and
 *  - it stops over-blocking an action the user explicitly asked for. (An earlier
 *    version reused the whole irreversible list unconditionally, which silently
 *    stripped `Tap(Send)` from "send mom a message" — the send simply never ran.)
 * `send`/`post` are [SendGuard]'s domain, so whatever survived it is left alone here.
 *
 * Deliberately asymmetric, like [AlarmGuard]. Refusing an action the user really
 * wanted costs them one more sentence; allowing one they never asked for can cost
 * them money — or a message to the wrong person.
 */
object SpendGuard {

    /** Words that mean the user is asking to commit money — any authorises any spend tap. */
    private val SPEND_WORDS = listOf(
        "checkout", "check out", "place the order", "place order", "buy",
        "purchase", "pay for", "pay now", "order it", "confirm the order",
    )

    /**
     * For a non-spend irreversible tap, the request words that authorise it. Matched
     * against the user's utterance (un-negated). `send`/`post` are handled by
     * [SendGuard] and are intentionally absent.
     */
    private val AUTHORISERS: Map<String, List<String>> = mapOf(
        "call" to listOf("call", "ring", "dial", "phone"),
        "share" to listOf("share", "forward", "send"),
        "delete" to listOf("delete", "remove", "clear", "empty", "discard"),
        "remove" to listOf("delete", "remove", "clear", "empty", "discard"),
    )

    /** True when the user's own words asked to commit money. */
    fun asksToSpend(utterance: String): Boolean = spendRequested(normalise(utterance))

    /**
     * The label of the first tap that would commit something the user did not ask
     * for, or null when the plan is safe to run as written.
     */
    fun stopsAt(utterance: String, steps: List<ScreenStep>): String? {
        val text = normalise(utterance)
        val step = steps.firstOrNull { it is ScreenStep.Tap && withheld(it.label, text) }
        return (step as? ScreenStep.Tap)?.label
    }

    /**
     * Truncates [steps] at the first unauthorised irreversible tap. Everything before
     * it still runs — searching, typing and adding to a basket are all recoverable, so
     * only the un-asked-for commit is withheld.
     */
    fun apply(utterance: String, steps: List<ScreenStep>): List<ScreenStep> {
        val text = normalise(utterance)
        val at = steps.indexOfFirst { it is ScreenStep.Tap && withheld(it.label, text) }
        return if (at < 0) steps else steps.take(at)
    }

    /** True when [label] is an irreversible tap the user did NOT authorise in [text]. */
    private fun withheld(label: String, text: String): Boolean {
        if (!Playbook.isIrreversible(label)) return false
        val l = normalise(label)
        // send/post belong to SendGuard; whatever survived it is authorised here.
        if (Negation.hasUnnegated(l, "send") || Negation.hasUnnegated(l, "post")) return false
        // A call/share/delete/remove is fine only if the user named that action.
        for ((keyword, authorisers) in AUTHORISERS) {
            if (Negation.hasUnnegated(l, keyword)) {
                return authorisers.none { Negation.hasUnnegated(text, it) }
            }
        }
        // Otherwise it commits money — it needs a spend word in the request.
        return !spendRequested(text)
    }

    private fun spendRequested(normalisedText: String): Boolean =
        normalisedText.isNotBlank() && SPEND_WORDS.any { Negation.hasUnnegated(normalisedText, it) }

    /** What to say when a plan was cut short, so the stop is never silent. */
    fun explain(label: String): String =
        "I've stopped short of $label — say the word and I'll do it."

    private fun normalise(text: String): String = text.lowercase()
        .replace("'", "")
        .replace("’", "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex(" +"), " ")
        .trim()
}
