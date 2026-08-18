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
     * Whether the background "Hey Jarvis" wake word runs — a mic foreground
     * service that lets JARVIS be summoned from any app. On by default (it is the
     * point of the feature), and turned off from the wake-word notification's Stop
     * action or the settings screen. Costs battery and shows an ongoing notice.
     */
    var backgroundWake: Boolean
        get() = prefs.getBoolean(KEY_BG_WAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_BG_WAKE, value).apply()

    /**
     * Whether the floating orb rides over other apps. On by default — it is the
     * only thing that makes JARVIS visible once you have left his screen, and
     * unlike the wake word it costs no battery and holds no microphone.
     *
     * It still needs the accessibility service, because that is the window type
     * it borrows (see [com.jarvis.os.control.OrbBubble]).
     */
    var floatingOrb: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_ORB, true)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_ORB, value).apply()

    /**
     * Where the user last put the floating orb, in screen pixels. `-1` means
     * "never moved" and the bubble chooses its own opening position.
     *
     * Stored rather than recomputed because a bubble that jumps back to the
     * middle of the screen every time the service restarts is one the user has to
     * keep moving out of the way of the same button.
     */
    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

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
        private const val KEY_BG_WAKE = "background_wake"
        private const val KEY_FLOATING_ORB = "floating_orb"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
    }
}
