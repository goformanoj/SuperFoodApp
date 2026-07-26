package com.jarvis.os.ai

/**
 * Chooses which AI provider answers. Groq is preferred (free, no billing);
 * Gemini is used if only its key is present.
 */
object Brain {

    fun hasKey(): Boolean = GroqClient.hasKey() || GeminiClient.hasKey()

    fun providerName(): String = when {
        GroqClient.hasKey() -> "Groq"
        GeminiClient.hasKey() -> "Gemini"
        else -> "none"
    }

    suspend fun generate(userText: String): String =
        if (GroqClient.hasKey()) {
            GroqClient.generate(userText)
        } else {
            GeminiClient.generate(userText)
        }
}
