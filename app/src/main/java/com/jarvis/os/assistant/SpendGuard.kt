package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenStep

/**
 * A one-shot plan must not be able to spend the user's money.
 *
 * From a device trace: the user said "can you go to blinkit and add some bread",
 * and the plan that ran was
 *
 *   Open(Blinkit), Type(bread), Enter, Tap(Add to cart), Tap(Search),
 *   Type(Jaam), Enter, Tap(Add to cart), **Tap(Checkout)**
 *
 * Nobody asked for a checkout. It only failed to happen because the typing step
 * broke first — the tap was queued and there was nothing in the way of it.
 *
 * [AgentLoop] already stops before an irreversible tap and [Playbook] refuses to
 * learn one, but the ordinary path — the one that runs on almost every command —
 * had no such check. This closes that, and takes the same position the other
 * three guards take: the prompt is probabilistic, and the cost of the rare miss
 * is borne by the user's bank account.
 *
 * Deliberately asymmetric, like [AlarmGuard]. Refusing a checkout the user really
 * wanted costs them one more sentence; allowing one they never asked for costs
 * them an order.
 */
object SpendGuard {

    /** Words that mean the user really is asking to complete a purchase. */
    private val SPEND_WORDS = listOf(
        "checkout", "check out", "place the order", "place order", "buy",
        "purchase", "pay for", "pay now", "order it", "confirm the order",
    )

    /**
     * True when the user's own words asked to commit money.
     *
     * Strict on purpose, and this is the opposite of [AlarmGuard]'s bias. There,
     * a loose reading only risks missing a real alarm request. Here a loose
     * reading is what LETS the checkout through, so it matches whole words only:
     * "buyer" is not "buy", and bare "order" is not in the list at all because
     * "add bread to my order" is not permission to place one.
     */
    fun asksToSpend(utterance: String): Boolean {
        val text = normalise(utterance)
        return text.isNotBlank() && SPEND_WORDS.any { containsWord(text, it) }
    }

    // The plain-string test SendGuard and AlarmGuard both use. A Regex here would
    // buy nothing and has already broken the build once.
    private fun containsWord(text: String, word: String): Boolean =
        text == word || text.startsWith("$word ") || text.endsWith(" $word") ||
            text.contains(" $word ")

    /**
     * The label of the first step that would spend or destroy something, or null
     * when the plan is safe to run as written.
     */
    fun stopsAt(utterance: String, steps: List<ScreenStep>): String? {
        if (asksToSpend(utterance)) return null
        val step = steps.firstOrNull { it is ScreenStep.Tap && Playbook.isIrreversible(it.label) }
        return (step as? ScreenStep.Tap)?.label
    }

    /**
     * Truncates [steps] at the first irreversible tap.
     *
     * Everything before it still runs — searching, typing and adding to a basket
     * are all recoverable, and stopping the whole errand would make JARVIS
     * useless for the part the user did ask for. Only the commit is withheld.
     */
    fun apply(utterance: String, steps: List<ScreenStep>): List<ScreenStep> {
        if (asksToSpend(utterance)) return steps
        val at = steps.indexOfFirst { it is ScreenStep.Tap && Playbook.isIrreversible(it.label) }
        return if (at < 0) steps else steps.take(at)
    }

    /** What to say when a plan was cut short, so the stop is never silent. */
    fun explain(label: String): String =
        "I've done the rest, but I've stopped before tapping $label — you didn't ask me to, " +
            "and I can't undo it. Tell me to $label and I will."

    private fun normalise(text: String): String = text.lowercase()
        .replace("'", "")
        .replace("’", "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex(" +"), " ")
        .trim()
}
