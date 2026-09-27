package com.jarvis.os.desktop

import com.jarvis.os.assistant.Markers
import com.jarvis.os.data.MemoryAction
import com.jarvis.os.data.MemoryActions

/**
 * The desktop's turn logic, kept pure so it is unit-tested (DesktopTurnTest).
 *
 * The Worker's default prompt is the phone's: it may answer an errand with device
 * markers (`<<OPEN|…>>`, `<<TAP|…>>`, `<<ALARM|…>>`). The desktop cannot carry those
 * out yet, so it must never show them, never pretend they ran, and say so plainly.
 * The context line below asks the model not to emit them; [process] is the net for
 * when it does anyway.
 */
object DesktopTurn {

    /** Turns sent as history — the same as the phone (AssistantEngine.MAX_CONTEXT_TURNS). */
    const val MAX_CONTEXT_TURNS = 10

    /** Remembered facts are capped because they ride on every request (phone: MAX_FACTS). */
    const val MAX_FACTS = 40

    const val PHONE_ONLY_NOTE =
        "That's something I can only do on your phone for now — desktop control is coming."

    /**
     * Markers that ask for a device action. `FILE`/`ENDFILE` are deliberately absent:
     * a file's body is plain text, and showing it is exactly right on a desktop.
     */
    private val PHONE_ACTION = Regex(
        """<<\s*(OPEN|OPENFILE|TAP|TYPE|ENTER|BACK|HOME|PICK|ALARM|CAL|NEEDS_ACTION)\b""",
        RegexOption.IGNORE_CASE,
    )

    data class Result(
        /** What to show the user — never contains a marker. */
        val text: String,
        val memory: List<MemoryAction>,
        /** The model asked for a device action the desktop could not perform. */
        val wantedPhoneAction: Boolean,
    )

    fun process(raw: String): Result {
        val (afterMemory, actions) = MemoryActions.parse(raw)
        val phone = PHONE_ACTION.containsMatchIn(afterMemory)
        val clean = Markers.strip(afterMemory)
        val text = when {
            !phone -> clean
            clean.isBlank() -> PHONE_ONLY_NOTE
            else -> "$clean\n\n$PHONE_ONLY_NOTE"
        }
        return Result(text, actions, phone)
    }

    /** Applies REMEMBER/FORGET with the phone's semantics (UserPreferences.remember/forget). */
    fun applyMemory(facts: List<String>, actions: List<MemoryAction>): List<String> {
        var out = facts
        for (action in actions) {
            out = when (action) {
                is MemoryAction.Remember -> {
                    val clean = action.fact.trim().take(MemoryActions.MAX_FACT)
                    if (clean.isEmpty() || out.any { it.equals(clean, ignoreCase = true) }) out
                    // Oldest out first, so the thing just said is never the one dropped.
                    else (out + clean).takeLast(MAX_FACTS)
                }
                is MemoryAction.Forget -> {
                    val needle = action.about.trim()
                    if (needle.isEmpty()) out else out.filterNot { it.contains(needle, ignoreCase = true) }
                }
            }
        }
        return out
    }

    /**
     * The grounding context for a desktop turn. Tells the model where it is running
     * and what it cannot do here, so it answers in words instead of phone markers.
     */
    fun context(nowText: String, memory: String): String = listOf(
        "Current date/time: $nowText.",
        "The user is talking to you in the JARVIS desktop app on their Windows laptop, by " +
            "typing. Phone actions (opening or controlling apps, tapping, typing into apps, " +
            "alarms, calendar changes) are NOT available here yet: do not emit any device-action " +
            "marker. If they ask for one, say briefly that it works from the phone for now, " +
            "and help in words where you can.",
        memory,
    ).filter { it.isNotBlank() }.joinToString("\n\n")
}
