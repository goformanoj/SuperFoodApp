package com.jarvis.os.voice

/**
 * Picks which installed text-to-speech voice sounds most like JARVIS.
 *
 * Android ships several voices per language and picks a bland default. The
 * difference between that default and the best installed voice is most of the
 * gap between "robot" and "assistant" — before paying for anything.
 *
 * Pure scoring on plain values (no Android types) so the preference order is
 * unit-tested rather than assumed.
 */
object VoicePreference {

    /** A voice is unusable if this is returned. */
    const val REJECT = -1

    /**
     * Higher is better. [name] is the engine's voice id, [quality] is
     * `android.speech.tts.Voice.QUALITY_*` (100..500).
     */
    fun score(
        language: String,
        country: String,
        name: String,
        quality: Int,
        networkRequired: Boolean,
    ): Int {
        if (!language.equals("en", ignoreCase = true)) return REJECT

        var score = 0

        // JARVIS reads as British. Indian and other English locales still work,
        // they just rank below the two that suit the character.
        score += when (country.uppercase()) {
            "GB" -> 300
            "US" -> 200
            else -> 100
        }

        if (isMale(name)) score += 150

        // QUALITY_VERY_HIGH (500) down to QUALITY_VERY_LOW (100).
        score += quality / 2

        // Network voices sound better but add latency to every reply and fail
        // offline. Only worth it when nothing local comes close.
        if (networkRequired) score -= 120

        return score
    }

    /**
     * Google's voice ids look like `en-gb-x-gbb#male_1-local`. Note "female"
     * contains "male", so the negative check has to come first — the obvious
     * `contains("male")` picks female voices roughly half the time.
     */
    fun isMale(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.contains("female") || lower.contains("fem_")) return false
        return lower.contains("male") || lower.contains("#m") || lower.contains("_m_")
    }


    /** Anything below this is worth offering to upgrade. QUALITY_NORMAL is 300. */
    const val GOOD_QUALITY = 400

    /**
     * A human label for the picker — voice ids like `en-gb-x-gbb#male_1-local`
     * mean nothing to anyone.
     */
    fun describe(country: String, name: String, quality: Int): String {
        val place = when (country.uppercase()) {
            "GB" -> "British"
            "US" -> "American"
            "IN" -> "Indian"
            "AU" -> "Australian"
            "CA" -> "Canadian"
            "IE" -> "Irish"
            "" -> "English"
            else -> country.uppercase()
        }
        val gender = if (isMale(name)) "male" else "female"
        val grade = when {
            quality >= 500 -> "very high quality"
            quality >= 400 -> "high quality"
            quality >= 300 -> "standard"
            else -> "basic"
        }
        return "$place $gender, $grade"
    }

    /** Slightly lowered: composed rather than chirpy. 1.0 is the engine default. */
    const val PITCH = 0.92f

    /** A touch under natural pace, so instructions land clearly. */
    const val SPEECH_RATE = 0.98f
}
