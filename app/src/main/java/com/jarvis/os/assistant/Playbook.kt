package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenActions
import com.jarvis.os.control.ScreenStep

/**
 * A route that worked: what the user asked for, and the steps that did it.
 *
 * [template] is the request with its variable part replaced by [Playbook.SLOT],
 * and [markers] is the step sequence in the same marker syntax the model emits,
 * with the variable part replaced by [Playbook.FILL]. Storing the steps as
 * markers rather than as a bespoke format means replay round-trips through
 * [ScreenActions.parse] — the same parser the model's own output goes through,
 * already tested.
 */
data class Route(
    val template: String,
    val markers: String,
    val uses: Int = 1,
    val lastUsedMillis: Long = 0L,
)

/**
 * Remembers the paths that worked, so a repeated task is replayed rather than
 * re-derived.
 *
 * From the device traces, "play Beat It" took eleven turns and four failed plans
 * to reach a playing song. The eleventh attempt was a perfectly good route —
 * open the app, tap search, type, enter, pick the first result — and JARVIS threw
 * it away. Next time it starts from nothing again.
 *
 * The slot is what makes this more than a cache. A route learned from "play Beat
 * It on YouTube" is stored as "play * on youtube", so "play Thriller on YouTube"
 * matches it and replays with the new text substituted. Without that, a playbook
 * would only ever help on a literally identical sentence, which nobody says twice.
 *
 * The variable part is found rather than guessed: whatever the sequence TYPED is,
 * by definition, the part that changes. If that text also appears in what the
 * user said, both sides get a slot; if it does not, the route is stored literally
 * and only matches the same request again.
 *
 * Pure, so all of it is unit-tested. Storage and the decision to replay live
 * elsewhere.
 */
object Playbook {

    /** Marks the variable part of a remembered request. */
    const val SLOT = "*"

    /** Marks the variable part of a remembered step sequence. */
    const val FILL = "{}"

    /** Shortest request worth remembering; below this the match is meaningless. */
    const val MIN_TEMPLATE_CHARS = 6

    /**
     * Taps that do something irreversible. A route containing one is never
     * learned.
     *
     * This is the whole safety position. Replaying a remembered route is an
     * action taken WITHOUT asking the model, so anything the route does happens
     * on the strength of a fuzzy string match. Opening an app and typing in a box
     * are recoverable; sending a message to another person is not, and a
     * near-miss match would send it to the wrong person or send the wrong thing.
     * Those routes are simply not remembered, and the model plans them fresh
     * every time, where [SendGuard] still applies.
     */
    private val IRREVERSIBLE = listOf(
        "send", "post", "share", "pay", "buy", "order", "checkout", "place order",
        "confirm", "delete", "remove", "call",
    )

    /**
     * Builds a route from a request and the steps that satisfied it, or null when
     * the pair is not worth remembering.
     */
    fun learn(utterance: String, steps: List<ScreenStep>): Route? {
        if (steps.size < 2) return null
        if (steps.any { it is ScreenStep.Tap && isIrreversible(it.label) }) return null

        val request = normalise(utterance)
        if (request.length < MIN_TEMPLATE_CHARS) return null

        val markers = markersOf(steps)
        val typed = steps.filterIsInstance<ScreenStep.Type>().firstOrNull()?.text

        if (typed.isNullOrBlank()) return Route(request, markers)

        val needle = normalise(typed)
        // The typed text has to actually be in the request for it to be the
        // variable part. "play Beat It" types "Beat It"; a request that types
        // something it never mentioned is not a template, it is a one-off.
        if (needle.length < 2 || !request.contains(needle)) return Route(request, markers)

        return Route(
            template = request.replace(needle, SLOT),
            markers = markers.replace(typed, FILL),
        )
    }

    /**
     * The steps for [utterance] if [route] covers it, or null.
     *
     * Returns parsed steps rather than a string so a caller cannot forget to run
     * them through the marker parser and its tolerances.
     */
    fun match(utterance: String, route: Route): List<ScreenStep>? {
        val request = normalise(utterance)
        if (!route.template.contains(SLOT)) {
            return if (request == route.template) parse(route.markers) else null
        }

        val slotAt = route.template.indexOf(SLOT)
        val prefix = route.template.substring(0, slotAt)
        val suffix = route.template.substring(slotAt + SLOT.length)
        if (request.length < prefix.length + suffix.length + 1) return null
        if (!request.startsWith(prefix) || !request.endsWith(suffix)) return null

        val value = request.substring(prefix.length, request.length - suffix.length).trim()
        if (value.isEmpty()) return null
        // Matching happens in lowercase, but what gets TYPED should keep the
        // user's capitals: a search box and a spoken PICK description both read
        // better with "Thriller" than "thriller".
        val cased = originalCase(utterance, value)
        // A slot value that is itself an instruction means the match is spurious:
        // "play * on youtube" should not swallow "play whatever you like and then
        // send it to mum on youtube".
        if (isIrreversible(value)) return null

        return parse(route.markers.replace(FILL, cased))
    }

    /** The steps as the marker string the model would have emitted. */
    fun markersOf(steps: List<ScreenStep>): String = steps.joinToString(" ") {
        when (it) {
            is ScreenStep.Open -> "<<OPEN|${it.app}>>"
            is ScreenStep.Tap -> "<<TAP|${it.label}>>"
            is ScreenStep.Type -> "<<TYPE|${it.text}>>"
            ScreenStep.Enter -> "<<ENTER>>"
            is ScreenStep.Pick -> "<<PICK|${it.description}>>"
            ScreenStep.Back -> "<<BACK>>"
            ScreenStep.Home -> "<<HOME>>"
        }
    }

    /** True when a label or phrase names something that cannot be undone. */
    fun isIrreversible(text: String): Boolean {
        val t = normalise(text)
        return IRREVERSIBLE.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") || t.contains(" $it ") }
    }

    /** [value] as the user actually typed or said it, capitals and all. */
    private fun originalCase(utterance: String, value: String): String {
        val at = utterance.lowercase().indexOf(value)
        // Normalising strips punctuation, so the offsets only line up for plain
        // words. When they do not, the lowercase form is a fine fallback.
        return if (at >= 0) utterance.substring(at, at + value.length) else value
    }

    private fun parse(markers: String): List<ScreenStep>? =
        ScreenActions.parse(markers).steps.takeIf { it.isNotEmpty() }

    private fun normalise(text: String): String = text.lowercase()
        .replace("'", "")
        .replace("’", "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex(" +"), " ")
        .trim()
}
