package com.jarvis.os.alarm

/**
 * Whether an alarm that has been set will actually be heard.
 *
 * Setting an alarm and having it ring are different things. The alarm stream has
 * its own volume, separate from media and ringer, and a phone that has been
 * turned down — or silenced entirely — accepts the alarm without complaint and
 * then says nothing at the appointed hour. JARVIS reporting "alarm set for
 * seven" while the alarm stream is at zero is the same class of failure as
 * claiming a tap worked when it did nothing.
 *
 * The judgement is pure so it can be tested; reading the device's actual volume
 * is the caller's job.
 */
object AlarmVolume {

    /** What the user should be told, if anything. */
    sealed interface Verdict {
        /** Loud enough; say nothing. */
        data object Fine : Verdict

        /** Audible but quiet. */
        data class Low(val percent: Int) : Verdict

        /** It will not be heard at all. */
        data object Silent : Verdict
    }

    /**
     * Below this fraction of maximum, an alarm is easy to sleep through. Chosen
     * low on purpose: warning about a perfectly usable volume every time an alarm
     * is set would train the user to ignore the warning.
     */
    const val LOW_FRACTION = 0.34f

    fun check(current: Int, max: Int): Verdict {
        if (max <= 0) return Verdict.Silent
        val level = current.coerceIn(0, max)
        if (level == 0) return Verdict.Silent
        val fraction = level.toFloat() / max
        return if (fraction < LOW_FRACTION) Verdict.Low(percent(level, max)) else Verdict.Fine
    }

    /** The level to raise the alarm stream to when the user asks for it loud. */
    fun targetFor(max: Int, requested: Float? = null): Int {
        if (max <= 0) return 0
        val f = (requested ?: 1f).coerceIn(0f, 1f)
        return Math.round(max * f).coerceIn(1, max)
    }

    /** A sentence for the spoken reply, or null when there is nothing to say. */
    fun warning(verdict: Verdict): String? = when (verdict) {
        Verdict.Fine -> null
        Verdict.Silent ->
            "Your alarm volume is off though, so it won't make a sound. Want me to turn it up?"
        is Verdict.Low ->
            "Your alarm volume is only ${verdict.percent} percent though — want it louder?"
    }

    /** True when the user is asking to change the alarm volume. */
    fun asksToRaise(utterance: String): Boolean {
        val t = utterance.lowercase()
        val aboutAlarm = listOf("alarm", "timer").any { t.contains(it) }
        val aboutVolume = listOf("volume", "loud", "sound", "louder", "turn it up", "max")
            .any { t.contains(it) }
        return aboutAlarm && aboutVolume
    }

    private fun percent(level: Int, max: Int): Int =
        Math.round(level * 100f / max).coerceIn(1, 100)
}
