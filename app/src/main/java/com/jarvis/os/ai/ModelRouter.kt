package com.jarvis.os.ai

/** Which class of model a turn deserves. */
enum class Tier { FAST, SMART }

/**
 * Chooses the model for a turn.
 *
 * "Open YouTube" and "set an alarm for seven" do not need a 70-billion-parameter
 * model; explaining something does. Groq's quotas are per model, so routing the
 * easy majority to the small one both preserves the big model's daily allowance
 * and answers faster.
 *
 * Conservative by design: anything that is not clearly a short, familiar command
 * goes to the smart model. A slow good answer beats a fast poor one, and the
 * saving only has to come from the easy cases to be worth having.
 *
 * Pure Kotlin, so the routing rules are unit-tested.
 */
object ModelRouter {

    /** Beyond this many words it is a conversation, not a command. */
    const val MAX_COMMAND_WORDS = 12

    /** Openers that mean "do a thing on this phone". */
    private val COMMAND_VERBS = setOf(
        "open", "launch", "start", "play", "search", "find", "type", "write", "send",
        "set", "add", "remind", "schedule", "cancel", "delete", "remove", "scroll",
        "tap", "click", "press", "go", "back", "home", "close", "stop", "pause",
        "resume", "next", "previous", "call", "text", "message", "show",
    )

    /** Cues that the user wants thinking rather than doing. */
    private val CONVERSATION_CUES = listOf(
        "why", "how come", "explain", "tell me about", "what do you think",
        "should i", "do you think", "difference between", "compare", "summarise",
        "summarize", "advice", "recommend", "opinion", "describe", "who is",
        "who was", "what is the", "what are the", "help me understand", "teach me",
    )

    /**
     * Always [Tier.SMART] for the assistant loop, on the evidence.
     *
     * Routing commands to the small model was tried and measured: in three device
     * traces it returned NO markers on every single command, so each one fell
     * through to the smart model anyway. That does not halve the requests, it
     * doubles them — the small model cannot hold the marker protocol, and the
     * protocol is what commands are made of.
     *
     * The fast tier is still used where it demonstrably works: the `<<PICK>>`
     * chooser and the Diagnostics ping, both of which carry a ten-token prompt
     * and answer with one value. Kept as a function rather than deleted so the
     * decision, and the reason, stay visible at the call site.
     */
    fun tierFor(utterance: String): Tier = Tier.SMART

    /**
     * True when the turn should have produced an action. Used to escalate: if the
     * fast model was asked to do something and came back with no command at all,
     * it probably fumbled the marker syntax, and the turn is worth one retry on
     * the smart model rather than a shrug.
     */
    fun expectsAction(utterance: String): Boolean {
        val text = normalise(utterance)
        val words = text.split(" ").filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.first() in COMMAND_VERBS
    }

    private fun normalise(text: String): String = text.lowercase()
        .replace("'", "")
        .replace("’", "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex(" +"), " ")
        .trim()
}
