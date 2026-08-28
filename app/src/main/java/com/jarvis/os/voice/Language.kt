package com.jarvis.os.voice

/**
 * The languages JARVIS can be told to understand and reply in.
 *
 * Deliberately five, not fifty. Five that between them reach a large share of
 * the world and each read completely differently on screen, so a user finds
 * their own at a glance rather than scrolling a locale list. The point is not a
 * translation engine — the hosted model already understands and writes all five
 * fluently — so this type translates nothing. It records which two languages the
 * user speaks, tells the recogniser what to listen for, and tells the model which
 * language to answer in. That is the whole feature: one enum, one preference, one
 * line of prompt.
 *
 * Pure and Compose-free, like the rest of `voice/`, so it is compiled and tested
 * off device — the recogniser and the speaker themselves are only ever proven on
 * a phone (Rule 5), which is exactly why the part that CAN be checked here is
 * kept separate from the part that cannot.
 */
enum class Language(
    /**
     * BCP-47 tag, used for both `SpeechRecognizer` (EXTRA_LANGUAGE and the API-33
     * allowed-detection set) and `TextToSpeech.setLanguage`. Region-qualified
     * because a bare "ar" or "en" leaves the engine to guess a region, and the
     * guess is often not the one a speaker wants.
     */
    val tag: String,
    /** English name, for the settings list. */
    val label: String,
    /** The language's own name in its own script — how a speaker recognises it. */
    val endonym: String,
) {
    English("en-US", "English", "English"),
    Hindi("hi-IN", "Hindi", "हिन्दी"),
    Arabic("ar-SA", "Arabic", "العربية"),
    French("fr-FR", "French", "Français"),
    German("de-DE", "German", "Deutsch"),
    ;

    companion object {
        /** What the app speaks before anyone has chosen — see [LanguagePrefs.DEFAULT]. */
        val DEFAULT = English

        /**
         * The [Language] for a stored or system tag, matching on the language
         * subtag so "en", "en-GB" and "en-US" all resolve to [English]. Returns
         * null for anything outside the supported five — a stored tag can outlive
         * the build that wrote it, so an unknown one must fall through cleanly
         * rather than crash.
         */
        fun fromTag(tag: String?): Language? {
            if (tag.isNullOrBlank()) return null
            val lang = tag.trim().substringBefore('-').lowercase()
            return entries.firstOrNull { it.tag.substringBefore('-').lowercase() == lang }
        }
    }
}

/**
 * The user's up-to-two preferred languages: a required [primary] and an optional
 * [secondary].
 *
 * The primary is the one the recogniser defaults to and the one JARVIS falls back
 * to when it cannot tell; the secondary is the other language it will also
 * understand. Two, because "max two language, and the user can select their two
 * preferred" — a cap that is also the honest limit of on-device language
 * detection, which degrades fast past a small allowed set.
 */
data class LanguagePrefs(
    val primary: Language,
    val secondary: Language?,
) {
    init {
        // The constructor is strict so a bad pair is impossible to hold; [of]
        // is the tolerant door that a settings screen uses.
        require(secondary != primary) { "the two languages must be different" }
    }

    /** The languages JARVIS understands: primary first, never empty, at most two. */
    val understood: List<Language>
        get() = listOfNotNull(primary, secondary)

    /** True once a second language has actually been chosen. */
    val isBilingual: Boolean get() = secondary != null

    /**
     * BCP-47 tags, primary first — the recogniser's allowed-detection set on
     * API 33+, and the ordered fallback below it.
     */
    val tags: List<String> get() = understood.map { it.tag }

    /** The stored form: "primaryTag[,secondaryTag]". */
    fun serialize(): String = tags.joinToString(",")

    companion object {
        /** English only — the app is monolingual until the user says otherwise. */
        val DEFAULT = LanguagePrefs(Language.English, null)

        /**
         * Build from a chosen list where order IS priority, tolerating anything a
         * UI might hand over: duplicates dropped, capped at two, empty falls back
         * to the default. It must never throw on a bad selection — the screen that
         * calls it cannot be allowed to crash on a stray double-tap.
         */
        fun of(chosen: List<Language>): LanguagePrefs {
            val distinct = chosen.distinct()
            return when {
                distinct.isEmpty() -> DEFAULT
                distinct.size == 1 -> LanguagePrefs(distinct[0], null)
                else -> LanguagePrefs(distinct[0], distinct[1])
            }
        }

        /**
         * Parse the stored form back into a preference, forgivingly: unknown or
         * retired tags are skipped, and a blank or all-unknown string is the
         * default. A stored tag outlives the build that wrote it, so this is a
         * real case, not defensive padding.
         */
        fun parse(stored: String?): LanguagePrefs {
            if (stored.isNullOrBlank()) return DEFAULT
            return of(stored.split(',').mapNotNull { Language.fromTag(it) })
        }
    }
}

/**
 * The one line handed to the model, naming the languages the user speaks and —
 * the load-bearing half — telling it to reply in the SAME one the user just used.
 *
 * The model already understands and writes all five, so this instruction is the
 * entire "interpretation" the feature needs; that is why it costs the prompt a
 * sentence rather than a translation pipeline. Kept short on purpose: the system
 * prompt rides on every request and is capped, so a new instruction has to earn
 * its characters.
 */
fun languagePromptLine(prefs: LanguagePrefs): String =
    if (!prefs.isBilingual) {
        "The user speaks ${prefs.primary.label}. Understand it and always reply in ${prefs.primary.label}."
    } else {
        val names = prefs.understood.joinToString(" and ") { it.label }
        "The user speaks $names. Understand either, and reply in whichever of the two the user used in their most recent message."
    }

/**
 * Which language a reply should be SPOKEN in, read from the reply's own script.
 *
 * A bilingual user's model reply comes back in whichever language they used, and
 * speaking a Hindi (Devanagari) or Arabic reply through an English voice is not
 * an accent — it is unintelligible noise, the worst possible failure of the
 * feature. Those two scripts are unmistakable, so they are detected directly.
 * The three Latin-script languages cannot be told apart by characters alone
 * (and an English voice reading French is at least intelligible), so they fall
 * back to the user's primary — never a wrong non-Latin guess.
 *
 * Only ever returns a language the user actually chose: if the script is not one
 * of theirs, the primary speaks it. Pure, so the script ranges are pinned by a
 * test rather than discovered on a phone.
 */
fun spokenLanguageFor(text: String, prefs: LanguagePrefs): Language {
    val hasDevanagari = text.any { it.code in 0x0900..0x097F } // Hindi script block
    val hasArabic = text.any { it.code in 0x0600..0x06FF } // Arabic script block
    return when {
        hasDevanagari && Language.Hindi in prefs.understood -> Language.Hindi
        hasArabic && Language.Arabic in prefs.understood -> Language.Arabic
        else -> prefs.primary
    }
}
