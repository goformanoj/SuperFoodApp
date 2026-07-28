package com.jarvis.os.data

import android.content.Context

/**
 * Settings the user owns: their standing instructions to JARVIS, and the theme.
 *
 * Custom instructions are appended to the model's context on every turn, so they
 * shape behaviour permanently rather than for one conversation — "call me sir",
 * "keep answers to one sentence", "I'm in Bangalore, use IST".
 */
class UserPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_user", Context.MODE_PRIVATE)

    var customInstructions: String
        get() = prefs.getString(KEY_INSTRUCTIONS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_INSTRUCTIONS, value.take(MAX_INSTRUCTIONS)).apply()

    var themeId: String
        get() = prefs.getString(KEY_THEME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    companion object {
        /**
         * Standing instructions ride on every single request, so an essay here is
         * a permanent tax on latency and cost. Long enough to be genuinely useful,
         * short enough not to dominate the prompt.
         */
        const val MAX_INSTRUCTIONS = 1000

        private const val KEY_INSTRUCTIONS = "custom_instructions"
        private const val KEY_THEME = "theme"
    }
}

/**
 * Wraps the user's standing instructions for the model, or returns "" when there
 * are none.
 *
 * Kept pure so it can be unit-tested: the wording matters, because these
 * instructions must guide JARVIS without letting a stray sentence override the
 * safety-shaped parts of the system prompt.
 */
fun formatCustomInstructions(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return "The user has standing preferences for how you behave. Follow them unless they " +
        "conflict with acting safely or truthfully:\n\"\"\"\n$trimmed\n\"\"\""
}
