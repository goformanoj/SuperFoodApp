package com.jarvis.os.ai

/** Which class of model a turn deserves. */
enum class Tier { FAST, SMART }

/**
 * Chooses the model for a turn in the assistant loop. It is always [Tier.SMART],
 * on the evidence.
 *
 * The idea was sound in the abstract: Groq's quotas are per model, "open YouTube"
 * does not need seventy billion parameters, so route the easy majority to the
 * small model and preserve the big one's daily allowance.
 *
 * It was then measured on real traffic and it failed outright. Across three
 * device traces llama-3.1-8b returned NO markers on a single command, so every
 * command escalated to the smart model anyway. That does not halve the requests,
 * it doubles them. The small model cannot hold the marker protocol, and for a
 * command the protocol IS the answer — the cost of a turn is not its length.
 *
 * So this is deliberately a function returning a constant rather than a deleted
 * class: the decision and its evidence stay visible at the call site, and the
 * next person to have this idea reads why it did not work before rebuilding it.
 *
 * The fast tier is still used where it demonstrably works — the `<<PICK>>`
 * chooser and the Diagnostics ping, which send about ten tokens and expect one
 * value back.
 *
 * Everything that supported the routing decision (the command-verb list, the
 * conversation cues, the word-count bound, and `expectsAction`, which escalated
 * a fumbled command) went with it. Keeping unreachable heuristics around would
 * have implied the app still routes when it does not; git history has them if
 * the experiment is ever worth repeating against a stronger small model.
 */
object ModelRouter {

    fun tierFor(utterance: String): Tier = Tier.SMART
}
