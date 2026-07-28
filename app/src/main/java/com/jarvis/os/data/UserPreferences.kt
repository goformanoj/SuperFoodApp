package com.jarvis.os.data

import android.content.Context
import org.json.JSONArray

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

    /**
     * Facts JARVIS decided to keep — nicknames, forms of address, standing
     * preferences it was told once. Newest last, so context reads chronologically.
     */
    fun learnedFacts(): List<String> = try {
        val raw = prefs.getString(KEY_FACTS, "[]").orEmpty()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Stores [fact], ignoring duplicates. Returns false when nothing was added,
     * so JARVIS does not claim to have learned something it already knew.
     */
    fun remember(fact: String): Boolean {
        val clean = fact.trim().take(MemoryActions.MAX_FACT)
        if (clean.isEmpty()) return false
        val current = learnedFacts()
        if (current.any { it.equals(clean, ignoreCase = true) }) return false
        // Oldest out first: a cap that dropped the newest would ignore the thing
        // the user just said.
        val next = (current + clean).takeLast(MAX_FACTS)
        saveFacts(next)
        return true
    }

    /** Drops any fact mentioning [about]. Returns how many went. */
    fun forget(about: String): Int {
        val needle = about.trim()
        if (needle.isEmpty()) return 0
        val current = learnedFacts()
        val kept = current.filterNot { it.contains(needle, ignoreCase = true) }
        if (kept.size != current.size) saveFacts(kept)
        return current.size - kept.size
    }

    fun forgetAll() = saveFacts(emptyList())

    private fun saveFacts(facts: List<String>) {
        val arr = JSONArray()
        facts.forEach { arr.put(it) }
        prefs.edit().putString(KEY_FACTS, arr.toString()).apply()
    }

    companion object {
        /** Everything here rides on every request, so the list cannot grow forever. */
        const val MAX_FACTS = 40

        /**
         * Standing instructions ride on every single request, so an essay here is
         * a permanent tax on latency and cost. Long enough to be genuinely useful,
         * short enough not to dominate the prompt.
         */
        const val MAX_INSTRUCTIONS = 1000

        private const val KEY_INSTRUCTIONS = "custom_instructions"
        private const val KEY_THEME = "theme"
        private const val KEY_FACTS = "learned_facts"
    }
}
