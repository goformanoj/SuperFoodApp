package com.jarvis.os.desktop

import com.jarvis.os.assistant.Markers
import com.jarvis.os.data.ChatTurn
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

    /** Sidebar title for a conversation: its first user line, trimmed to fit. */
    fun titleFor(turns: List<ChatTurn>): String {
        val first = turns.firstOrNull { it.role == ChatTurn.USER }?.content
            ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?: return "New chat"
        val clean = Markers.strip(first).replace(Regex("\\s+"), " ").trim().ifEmpty { return "New chat" }
        return if (clean.length <= TITLE_MAX) clean else clean.take(TITLE_MAX - 1).trimEnd() + "…"
    }

    const val TITLE_MAX = 42

    /** A user-typed title, tidied: one line, collapsed spaces, capped; null if blank. */
    fun cleanTitle(raw: String): String? {
        val one = raw.replace(Regex("\\s+"), " ").trim()
        if (one.isEmpty()) return null
        return if (one.length <= TITLE_MAX) one else one.take(TITLE_MAX - 1).trimEnd() + "…"
    }

    /**
     * The grounding context for a desktop turn. Tells the model where it is running
     * and what it cannot do here, so it answers in words instead of phone markers.
     */
    fun context(nowText: String, memory: String): String = listOf(
        "Current date/time: $nowText.",
        // The agent's tools cover tasks, reminders, notes, memory, search and opening apps or
        // sites; this line must NOT contradict them. (An older version said reminders were
        // unavailable, and the live model obeyed it — asking questions instead of acting.)
        "The user is talking to you in the JARVIS desktop app on their Windows laptop. Use your " +
            "tools to add tasks, set reminders, save notes, remember things, search their past " +
            "chats and open apps or websites. Phone-only actions (calls, texts, phone alarms, " +
            "tapping inside phone apps) aren't available from the laptop: do not emit any " +
            "device-action marker; say those work from the phone.",
        memory,
    ).filter { it.isNotBlank() }.joinToString("\n\n")
}
