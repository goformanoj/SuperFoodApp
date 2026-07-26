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

    suspend fun generate(messages: List<ChatTurn>, context: String): String =
        if (GroqClient.hasKey()) {
            GroqClient.generate(messages, context)
        } else {
            GeminiClient.generate(messages, context)
        }
}
