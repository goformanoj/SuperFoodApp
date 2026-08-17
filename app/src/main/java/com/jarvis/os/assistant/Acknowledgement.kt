package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenStep

/**
 * What JARVIS says when the model gave him markers and no words of his own.
 *
 * ## Why this exists
 *
 * Every reply that carried an action used to be the same two syllables: "On it."
 * Ask him to open Spotify, search Amazon Music, add bread to a basket — "On it."
 * every time. It reads as a machine acknowledging a command rather than a person
 * answering you, and it is the single thing that most made the app feel canned.
 * The same was true of a handful of other stock lines: "Done, that's set.",
 * "Okay, I've added it to your calendar.", "Yes?", "Anytime."
 *
 * Two things fix that, and only one of them lives here.
 *
 * The real fix is that **the model's own sentence should be what he says.** The
 * system prompt already asks for one short natural line before the markers, and
 * when it arrives it is used verbatim — nothing in this file is consulted. This
 * is only the floor for when the model emits bare markers and nothing else.
 *
 * So the floor is built to be worth hearing rather than merely present:
 *
 *  - **Say what is actually happening.** "Opening Spotify" beats "On it" because
 *    it is the same length and tells the user something. It comes from the plan,
 *    so it cannot be wrong about which app is opening.
 *  - **Never the same line twice in a row.** [turn] rotates the wording. It is a
 *    plain counter rather than a random draw so this whole object stays pure and
 *    the tests can pin exact strings.
 *
 * ## What this is NOT
 *
 * It never suppresses or edits anything the model said, and it never adds a word
 * to a reply that already has one. It only fills a silence.
 */
object Acknowledgement {

    /**
     * A line for a plan that is about to run, drawn from what the plan does.
     *
     * [turn] is any monotonically increasing count of turns; only its remainder
     * matters.
     */
    fun forPlan(steps: List<ScreenStep>, turn: Int): String {
        val typed = steps.filterIsInstance<ScreenStep.Type>().firstOrNull()?.text?.trim()
        val app = steps.filterIsInstance<ScreenStep.Open>().firstOrNull()?.app?.trim()
        val picked = steps.filterIsInstance<ScreenStep.Pick>().firstOrNull()?.description?.trim()

        // Searching for something names the thing, which is the most useful
        // sentence available and reassures the user it heard the right words.
        if (!typed.isNullOrEmpty()) {
            return pick(
                turn,
                if (app.isNullOrEmpty()) "Searching for $typed." else "Looking for $typed in $app.",
                if (app.isNullOrEmpty()) "Let me find $typed." else "Let me find $typed in $app.",
                "Looking up $typed now.",
            )
        }
        if (!app.isNullOrEmpty()) {
            return pick(turn, "Opening $app.", "Getting $app up now.", "Sure, opening $app.")
        }
        if (!picked.isNullOrEmpty()) {
            return pick(turn, "Finding $picked.", "Let me look for $picked.", "Looking for $picked.")
        }
        return pick(turn, "Doing that now.", "On it.", "Right away.")
    }

    /** Confirmation for calendar work, when the model said nothing itself. */
    fun forCalendar(added: Int, deleted: Int, turn: Int): String = when {
        added > 0 && deleted > 0 ->
            pick(turn, "Moved it.", "That's rescheduled.", "Shifted it for you.")
        added > 0 ->
            pick(turn, "That's in your calendar.", "Added to your calendar.", "Got it in the diary.")
        deleted > 0 ->
            pick(turn, "Taken off your calendar.", "That's removed.", "Cleared it from your calendar.")
        else -> pick(turn, "Done.", "All done.", "That's done.")
    }

    fun forAlarm(turn: Int): String =
        pick(turn, "That's set.", "Alarm set.", "Set for you.")

    fun forFile(turn: Int): String =
        pick(turn, "Saved it to your Files.", "That's in your Files.", "Written and saved to Files.")

    /**
     * The answer to being summoned — by the wake word, the assist gesture, or a
     * tap. Was always "Yes?", which every single session opened with.
     */
    fun summoned(turn: Int): String =
        pick(turn, "Yes?", "Go ahead.", "Mm-hm?", "I'm listening.")

    /** The reply to "thank you Jarvis", which ends the session. */
    fun farewell(turn: Int): String =
        pick(turn, "Anytime.", "Any time at all.", "Happy to help.", "Sure thing.")

    private fun pick(turn: Int, vararg options: String): String =
        options[Math.floorMod(turn, options.size)]
}
