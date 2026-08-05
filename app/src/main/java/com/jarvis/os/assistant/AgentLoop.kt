package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenActions
import com.jarvis.os.control.ScreenStep

/** What the model decided to do next, given the goal and the live screen. */
sealed interface AgentMove {
    /** Take exactly one action, then look again. */
    data class Act(val step: ScreenStep) : AgentMove

    /** The goal is met. */
    data object Done : AgentMove

    /** The next action is irreversible, or the goal is ambiguous: ask first. */
    data class Ask(val question: String) : AgentMove

    /** Nothing sensible to do from here. */
    data class Blocked(val reason: String) : AgentMove
}

/**
 * One action at a time, looking at the screen between each.
 *
 * The old executor planned a whole sequence up front, from the screen it could
 * see at the moment of asking. That is why every failure in the device traces is
 * at step two or later: step one runs against a real screen, and every step
 * after it was planned against a screen that did not exist yet. "Open Blinkit and
 * add some items" cannot be planned that way at all — not because Blinkit is
 * unfamiliar, but because the second tap depends on what the first one revealed.
 *
 * So the loop replaces planning-then-hoping: decide ONE step from what is
 * actually on screen, run it, look again, decide the next. A failed step stops
 * being a special case needing "recovery" — it is simply the next observation.
 *
 * Two limits make this safe to run unattended.
 *
 * A step budget, because a model that cannot find what it wants will otherwise
 * tap around a stranger's phone indefinitely. When it runs out JARVIS stops and
 * says where it got to, rather than pretending.
 *
 * And a hard stop before anything irreversible. The loop acts without asking
 * between steps, so "shop something for me" must not be able to place an order:
 * the moment the next step would send, buy, pay or delete, the loop stops and
 * puts the decision to the user. That is the same position [SendGuard] takes for
 * one-shot plans and [Playbook] takes for replay, for the same reason.
 *
 * Pure: the prompt, the network call and the tapping all live elsewhere.
 */
object AgentLoop {

    /**
     * How many actions the loop may take for one goal.
     *
     * Small on purpose. A budget of 18 was tried and it is far too much rope: a
     * trace shows eighteen consecutive taps on a stranger's shopping app —
     * "Use my Current Location" four times, "Search for" five times, "Shop for
     * ₹99" three times — none of which advanced the errand. The user's verdict
     * was "it just clicked sooo many random buttons", and they were right.
     *
     * The fix is not a bigger allowance, it is stopping when nothing is
     * changing: see [repeats] and [STALL_LIMIT]. With those, most real errands
     * finish inside this, and a confused one costs a handful of taps rather than
     * a rampage.
     */
    const val MAX_STEPS = 8

    /** How many no-progress moves are tolerated before the loop gives up. */
    const val STALL_LIMIT = 2

    /** A step may not be chosen more than this many times in one errand. */
    const val REPEAT_LIMIT = 2

    /**
     * Stand-in reason when the reply carried no words at all.
     *
     * Internal, and deliberately never spoken — see [blockedMessage].
     */
    const val NO_STEP = "no next step"

    /** Reason given when the model picks a step it has already taken. */
    const val GOING_IN_CIRCLES = "going in circles"

    /** Reason given when the model re-picks the step that just failed. */
    const val ALREADY_FAILED = "that step has already failed here"

    /** Blocked reasons JARVIS produced itself, which must never be read aloud. */
    val INTERNAL_REASONS = setOf(NO_STEP, ALREADY_FAILED, GOING_IN_CIRCLES)

    /**
     * True when [step] has been chosen too often already.
     *
     * The `avoid` check only ever caught the step that FAILED. Most of the
     * thrashing in the trace was steps that "succeeded" and changed nothing —
     * `Open(Zepto)` three times running, each logged "already in Zepto — not
     * relaunching". Success is not progress.
     */
    fun repeats(step: ScreenStep, taken: List<ScreenStep>): Boolean =
        taken.lastOrNull() == step || taken.count { it == step } >= REPEAT_LIMIT

    /**
     * True when [steps] describe an errand inside an app rather than one action.
     *
     * This is the shape that cannot be planned up front: open something, then
     * interact with whatever it shows. A device trace has the model planning
     * `Tap(Search)` for Blinkit, whose search control is actually labelled
     * "Search for atta, dal, coke and more" — unknowable before the app opened.
     * Everything after the first step was guessed against a screen that did not
     * exist, so those plans are treated as a suggestion and the loop drives
     * instead, looking before each action.
     *
     * One-step commands and follow-ups in the app already open are left alone:
     * they are planned against the screen the user is actually looking at, they
     * work today, and routing them through a round-trip per action would make
     * them slower for nothing.
     */
    fun isErrand(steps: List<ScreenStep>): Boolean {
        if (steps.firstOrNull() !is ScreenStep.Open) return false
        return steps.drop(1).count { it !is ScreenStep.Open } >= 2
    }

    /** The leading app launches, which are safe to run before looking. */
    fun opensIn(steps: List<ScreenStep>): List<ScreenStep> = steps.takeWhile { it is ScreenStep.Open }

    /** The model says the goal is met. */
    private val DONE = Regex("""<<\s*DONE\s*>{1,2}""", RegexOption.IGNORE_CASE)

    /** The model wants the user to decide. */
    private val ASK = Regex("""<<\s*ASK\s*\|([^>\n]*)>{1,2}""", RegexOption.IGNORE_CASE)

    /**
     * Reads one move out of the model's reply.
     *
     * Takes the FIRST action only, even when the model emits several: the whole
     * point is that anything after the first is planned against a screen that
     * does not exist yet. The model is told to send one; this makes it true
     * whether or not it complied.
     */
    fun parseMove(
        reply: String,
        avoid: ScreenStep? = null,
        taken: List<ScreenStep> = emptyList(),
    ): AgentMove {
        ASK.find(reply)?.let { match ->
            val question = match.groupValues[1].trim()
            if (question.isNotEmpty()) return AgentMove.Ask(question)
        }

        val steps = ScreenActions.parse(reply).steps
        val first = steps.firstOrNull()

        // Never re-run the step that just failed. The prompt asks for this and a
        // device trace shows the model doing it anyway: told that
        // Tap("Search for atta, dal, coke and more") had failed, it answered with
        // that exact tap, three times running, and the user watched JARVIS poke
        // the same control until they gave up. Prompts are probabilistic; this is
        // the same reason SendGuard exists.
        if (first != null && avoid != null && first == avoid) {
            return AgentMove.Blocked(ALREADY_FAILED)
        }

        // And never keep doing what is not working. This catches the far more
        // common case in the trace: steps that reported success and moved
        // nothing.
        if (first != null && repeats(first, taken)) {
            return AgentMove.Blocked(GOING_IN_CIRCLES)
        }

        // DONE only counts when there is no action alongside it — a reply that
        // says "done" while still tapping has not finished.
        if (first == null) {
            return if (DONE.containsMatchIn(reply)) {
                AgentMove.Done
            } else {
                AgentMove.Blocked(ScreenActions.parse(reply).clean.ifBlank { NO_STEP })
            }
        }

        if (needsConfirmation(first)) {
            return AgentMove.Ask(confirmationFor(first))
        }
        return AgentMove.Act(first)
    }

    /**
     * True when [step] would do something that cannot be undone.
     *
     * Only taps are checked. Typing into a box, opening an app and going back are
     * all recoverable; it is the tap on "Place order" that spends money.
     */
    fun needsConfirmation(step: ScreenStep): Boolean =
        step is ScreenStep.Tap && Playbook.isIrreversible(step.label)

    /** What to ask before taking an irreversible step. */
    fun confirmationFor(step: ScreenStep): String {
        val what = (step as? ScreenStep.Tap)?.label ?: "that"
        return "I'm about to tap $what, which I can't undo. Shall I?"
    }

    /** True when the loop has spent its budget. */
    fun exhausted(stepsTaken: Int): Boolean = stepsTaken >= MAX_STEPS

    /**
     * What to say when the budget runs out.
     *
     * Names the goal and the count rather than claiming success or failing
     * silently — the user needs to know it stopped, and where.
     */
    fun exhaustedMessage(goal: String): String =
        "I've taken $MAX_STEPS steps towards \"$goal\" and I'm not there yet, so I've " +
            "stopped rather than keep guessing. Tell me what to do next."

    /**
     * What to say when the loop cannot find a next step.
     *
     * Always returns something speakable, which is the whole point. A dead end
     * that only writes to the log is indistinguishable from JARVIS still
     * working: the user waits, hears nothing, and eventually asks again. That is
     * precisely the "look how many tries it took me" complaint the loop exists
     * to fix, so failing quietly here would reintroduce it at the last step.
     *
     * The model's own words are preferred when it actually explained itself —
     * "I can't see a cart on this screen" tells the user something a generic
     * line cannot. Fragments and the internal [NO_STEP] placeholder are not
     * spoken; a stray word read aloud is worse than a plain admission.
     */
    fun blockedMessage(goal: String, reason: String): String {
        val explanation = reason.trim()
        // Reasons JARVIS wrote for itself are diagnostics, not speech. A trace
        // has it saying "that step has already failed here. I've stopped there
        // rather than guess." straight to the user, which explains nothing to
        // someone who cannot see the log.
        val speakable = explanation.length >= 12 &&
            INTERNAL_REASONS.none { explanation.equals(it, ignoreCase = true) } &&
            explanation.split(Regex("\\s+")).count { it.isNotBlank() } >= 3
        if (!speakable) {
            return "I got stuck trying to \"$goal\" — I can't see what to do from this " +
                "screen, so I've stopped rather than guess. What next?"
        }
        val ended = if (explanation.last() in ".!?") explanation else "$explanation."
        return "$ended I've stopped there rather than guess. What next?"
    }

    /**
     * A compact history line for the next prompt: what has already been done.
     *
     * Kept short deliberately. It rides on every step of the loop, and a
     * transcript that grows without bound is what makes a long errand hit the
     * per-minute token limit halfway through.
     */
    fun historyOf(steps: List<ScreenStep>, keep: Int = 6): String {
        if (steps.isEmpty()) return "nothing yet"
        val recent = steps.takeLast(keep)
        val prefix = if (steps.size > keep) "…${steps.size - keep} earlier, then " else ""
        return prefix + recent.joinToString(", ") { describe(it) }
    }

    private fun describe(step: ScreenStep): String = when (step) {
        is ScreenStep.Open -> "opened ${step.app}"
        is ScreenStep.Tap -> "tapped ${step.label}"
        is ScreenStep.Type -> "typed \"${step.text}\""
        ScreenStep.Enter -> "submitted"
        is ScreenStep.Pick -> "chose ${step.description}"
        ScreenStep.Back -> "went back"
        ScreenStep.Home -> "went home"
    }
}
