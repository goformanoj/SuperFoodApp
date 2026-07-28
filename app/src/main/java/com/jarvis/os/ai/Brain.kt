package com.jarvis.os.ai

import com.jarvis.os.data.ChatTurn

/**
 * Chooses which AI provider answers. Groq is preferred (free, no billing);
 * Gemini is used if only its key is present. Both receive the conversation
 * history and a grounding [context].
 */
object Brain {

    fun hasKey(): Boolean = GroqClient.hasKey() || GeminiClient.hasKey()

    fun providerName(): String = when {
        GroqClient.hasKey() -> "Groq"
        GeminiClient.hasKey() -> "Gemini"
        else -> "none"
    }

    suspend fun generate(
        messages: List<ChatTurn>,
        context: String,
        tier: Tier = Tier.SMART,
    ): String =
        if (GroqClient.hasKey()) {
            GroqClient.generate(messages, context, tier = tier)
        } else {
            GeminiClient.generate(messages, context)
        }


    /**
     * A minimal round-trip for Diagnostics. Deliberately NOT [generate]: that
     * carries the full assistant prompt (~2,000 tokens), so a health check that
     * only needs to hear "OK" was costing as much as a real conversation turn and
     * pushing the account toward its tokens-per-minute limit.
     */
    suspend fun ping(): String =
        if (GroqClient.hasKey()) {
            GroqClient.generate(
                messages = listOf(ChatTurn(ChatTurn.USER, "Reply with just: OK")),
                context = "",
                systemOverride = "Reply with exactly: OK",
                tier = Tier.FAST,
            )
        } else {
            GeminiClient.generate(listOf(ChatTurn(ChatTurn.USER, "Reply with just: OK")), "")
        }

    /**
     * Which of the things currently on screen matches [description]? Returns a
     * 1-based index, or null if nothing does.
     *
     * This is what stops the model guessing a label for something that does not
     * exist yet when a plan is written — "the first video result" cannot be
     * matched as text, only chosen once the results are on screen.
     *
     * Groq only for now: Gemini has no chooser yet, so with only a Gemini key
     * this returns null and the step reports an honest failure rather than
     * tapping something arbitrary.
     */
    suspend fun choose(description: String, options: List<String>): Int? =
        if (GroqClient.hasKey()) GroqClient.chooseIndex(description, options) else null
}
