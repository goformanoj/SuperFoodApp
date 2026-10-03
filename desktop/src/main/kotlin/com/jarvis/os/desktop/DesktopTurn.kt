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

    /** Leading lines of a user message that record what came with it (a file, a screenshot). */
    const val DOC_MARK = "📎"
    const val SHOT_MARK = "🖥"
    const val ROUTINE_MARK = "⏰"

    /** Splits a user message into its attachment lines and the words the user typed. Pure; tested. */
    fun splitAttachments(content: String): Pair<List<String>, String> {
        val lines = content.lines()
        val marks = lines.takeWhile { it.startsWith(DOC_MARK) || it.startsWith(SHOT_MARK) || it.startsWith(ROUTINE_MARK) }
        return marks to lines.drop(marks.size).joinToString("\n").trim()
    }

    /**
     * Rides on a routine's run. Nobody is watching: the model must produce the result, not
     * ask questions, and knows that approval-needing steps will be declined.
     */
    fun routineNote(name: String, manual: Boolean): String =
        "This is the user's routine “$name”, " + (if (manual) "run now from the Scheduled screen" else "running by itself on its schedule") +
            ". The user is not watching the chat: do not ask questions; use your tools and give the result directly, " +
            "short enough to read in a notification or hear in under a minute. Anything that needs the user's approval " +
            "(deleting, sending, looking at the screen) will be declined, so don't attempt it."

    /**
     * A reply as plain text for a Windows notification or speech: no markdown symbols, one
     * line per paragraph or list item. Pure; tested.
     */
    fun plain(text: String): String = text.lines()
        .map { l ->
            l.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
                .replace(Regex("^\\s*[-*•]\\s+"), "• ")
                .replace(Regex("\\*\\*|__|`"), "")
                .replace(Regex("(?<![\\w*])\\*(?!\\s)([^*]+)(?<!\\s)\\*"), "$1")
                .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
                .trim()
        }
        .filter { it.isNotEmpty() && !it.matches(Regex("[-*_]{3,}")) }
        .joinToString("\n")

    /** Sent while file access is off: tell the user how to turn it on, don't interrogate them. */
    const val FILES_OFF_NOTE =
        "The user has NOT allowed you to see files or folders on this laptop (Settings → Permissions → Laptop files), " +
            "so you have no file or folder tools right now. If they ask you to open, find, list or read anything on the " +
            "laptop (a folder, a project, a document that isn't attached), do not ask which drive, folder or file type: " +
            "say in one or two sentences that file access is switched off and that they can turn it on in Settings → " +
            "Permissions → Laptop files. A document they attach to the chat can still be read."

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
            ?.let { splitAttachments(it).second }
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
    /**
     * The calendar the model should read dates from, rather than work them out:
     * "Rest of this week: Tue 29 Sep, … Sun 4 Oct. Next week: Mon 5 Oct, … Sun 11 Oct."
     * Models do weekday arithmetic badly (live: "by Friday" became Thursday 1 Oct, and
     * "next Tuesday" a Monday); a lookup they can't miss. Weeks run Monday to Sunday.
     */
    fun weekAhead(today: java.time.LocalDate): String {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)
        val nextMonday = today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
        val rest = generateSequence(today.plusDays(1)) { it.plusDays(1) }.takeWhile { it < nextMonday }.toList()
        val next = (0L..6L).map { nextMonday.plusDays(it) }
        return (if (rest.isEmpty()) "" else "Rest of this week: " + rest.joinToString(", ") { it.format(fmt) } + ". ") +
            "Next week: " + next.joinToString(", ") { it.format(fmt) } + "."
    }

    fun context(
        nowText: String, memory: String, attachedDocs: List<String> = emptyList(), today: java.time.LocalDate? = null,
        /** The connected Google account (Calendar + Gmail), or null. */
        google: String? = null,
        /** Whether the user has allowed JARVIS to look at files and folders on this laptop (Settings → Permissions). */
        laptopFiles: Boolean = true,
    ): String = listOf(
        "Current date/time: $nowText." + (today?.let { " " + weekAhead(it) } ?: ""),
        // The agent's tools cover tasks, reminders, notes, memory, search, documents, files,
        // the web and the screen; this line must NOT contradict them. (An older version said
        // reminders were unavailable, and the live model obeyed it, asking instead of acting.)
        "The user is talking to you in the JARVIS desktop app on their Windows laptop. Use your " +
            "tools to add tasks, set reminders, save notes, remember things, search their past " +
            "chats, read their documents, " + (if (laptopFiles) "find files and look inside folders on the laptop, " else "") + "search the web, look at the " +
            "screen, and open apps, files or websites. Phone-only actions (calls, texts, phone alarms, " +
            "tapping inside phone apps) aren't available from the laptop: do not emit any " +
            "device-action marker; say those work from the phone.",
        // Without this the model has no file tools and no idea why, so it quizzes the user ("which drive? what
        // kind of file?") instead of saying the one thing that unblocks them: the permission is off.
        if (laptopFiles) "" else FILES_OFF_NOTE,
        if (google == null) "" else
            "Google Calendar and Gmail are connected" + (if ('@' in google) " ($google)" else "") +
                ": use them for the user's schedule and email. Emails are drafted, and only sent when the user asks and approves.",
        if (attachedDocs.isEmpty()) "" else
            "Documents attached to this chat (read them with read_document; \"this\" or \"it\" means the latest): " +
                attachedDocs.joinToString("; ") + ".",
        memory,
    ).filter { it.isNotBlank() }.joinToString("\n\n")
}
