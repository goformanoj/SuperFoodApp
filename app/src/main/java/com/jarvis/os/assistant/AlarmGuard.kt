package com.jarvis.os.assistant

import com.jarvis.os.alarm.AlarmAction

/**
 * An alarm the user did not ask for is a real-world event at a real-world time,
 * and they find out about it when it goes off.
 *
 * From a device trace: the user said "play Beat It" and the reply carried
 * `<<ALARM|TIMER|600|nap>>`. A ten-minute timer called "nap" was actually set.
 * Nothing in the utterance was about time at all — the model simply reached for
 * a marker it had been taught. Later in the same session, asked to play a song,
 * it volunteered "what time would you like to set an alarm to wake up to this
 * song?".
 *
 * Prompt wording cannot be relied on for this, for the same reason it could not
 * be relied on for sending messages: it is probabilistic, and the cost of the
 * rare miss is borne by the user at six in the morning. So the guard is in code,
 * next to [SendGuard], and it is tested.
 *
 * Deliberately generous about what counts as asking. Missing a real alarm
 * request is an annoyance the user corrects in one sentence; setting a phantom
 * one is an alarm going off in a meeting.
 */
object AlarmGuard {

    /** Words that mean the user is talking about a scheduled noise. */
    private val ALARM_WORDS = listOf(
        "alarm", "timer", "wake me", "wake up", "remind me at", "remind me in",
        "countdown", "snooze", "set it for", "go off",
    )

    /**
     * Time-shaped phrasing, for requests that never name the mechanism —
     * "in ten minutes", "at half seven", "quarter past six".
     */
    private val TIME_WORDS = listOf(
        "o'clock", "oclock", "am", "pm", "minute", "minutes", "hour", "hours",
        "second", "seconds", "half past", "quarter past", "quarter to", "noon",
        "midnight", "morning", "evening", "tonight", "tomorrow",
    )

    /** True when the user's words are plausibly about setting an alarm or timer. */
    fun asksForAlarm(utterance: String): Boolean {
        val text = normalise(utterance)
        if (text.isBlank()) return false
        if (ALARM_WORDS.any { text.contains(it) }) return true
        // A bare time on its own is usually an answer to "what time?", which is
        // exactly the turn an alarm gets confirmed on.
        if (TIME_WORDS.any { containsWord(text, it) } && looksLikeCommand(text)) return true
        return DIGIT_TIME.containsMatchIn(text)
    }

    /**
     * Drops [alarms] when [utterance] was not about alarms at all.
     *
     * The whole list goes or none of it does: a reply that invented one alarm
     * marker out of nowhere has not earned the benefit of the doubt on a second.
     */
    fun apply(utterance: String, alarms: List<AlarmAction>): List<AlarmAction> =
        if (alarms.isEmpty() || asksForAlarm(utterance)) alarms else emptyList()

    /** "7:30", "07 30", "at 7" — a time the user might be confirming. */
    private val DIGIT_TIME = Regex("""\b\d{1,2}[:.]\d{2}\b|\bat \d{1,2}\b""")

    private fun looksLikeCommand(text: String): Boolean =
        listOf("set", "make", "put", "wake", "remind", "start", "in ", "at ", "for ")
            .any { text.contains(it) }

    private fun containsWord(text: String, word: String): Boolean =
        Regex("(^|\\W)${Regex.escape(word)}(\\W|$)").containsMatchIn(text)

    private fun normalise(text: String): String = text.lowercase()
        .replace("'", "")
        .replace("’", "")
        .replace(Regex("[^a-z0-9: .]"), " ")
        .replace(Regex(" +"), " ")
        .trim()
}
