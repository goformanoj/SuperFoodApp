package com.jarvis.os.agent

/**
 * Decides, in CODE before the model is ever called (Rule 6 — never left to the
 * prompt), whether a phone chat turn should go through the new native tool-calling
 * loop ([AgentLoop]/[ToolBox]) or stay on the existing `<<MARKER>>` pipeline
 * (`AssistantEngine.ask()`'s own `CalendarActions`/`AlarmActions`/`MemoryActions`/
 * `ArtifactActions`/`ScreenActions` chain).
 *
 * This is NOT a general intent classifier — it only has to answer one narrow
 * question: does this utterance fit entirely inside what [ToolBox] can actually do
 * today (tasks, reminders, notes, searching past tasks/notes)? Calendar, alarms,
 * timers, screen control and files have NO tool-calling equivalent yet, so routing
 * one of those here would silently fail (or make the model apologise) instead of
 * doing what the old, working system already does correctly. Memory
 * (`remember`/`forget`) is deliberately excluded too, even though [ToolBox] has
 * those tools: the OLD `<<REMEMBER>>`/`<<FORGET>>` marker path writes to a
 * different store (`UserPreferences`) than the new one (`data.Brain`), and routing
 * memory here before those two stores are reconciled would split a user's
 * remembered facts across two places invisibly — a deliberate, separate decision,
 * not done by accident here.
 *
 * Designed to fail SAFE: a false negative (missing a genuine task/reminder/note
 * request) just means today's unchanged behavior — no regression, since nothing
 * handled tasks/reminders/notes in live chat before this existed at all. A false
 * positive (routing something that actually needed calendar/alarm/screen/files
 * here) is the real cost, so [EXCLUDE] is checked FIRST and wins over [INCLUDE]
 * whenever both match — but deliberately does NOT include verbs like "call",
 * "text" or "message" that are common REMINDER CONTENT ("remind me to call mom"),
 * even though they can also mean a screen action ("message mom hello") — excluding
 * them would break the single most common reminder phrasing to guard against a
 * rarer case the old system already handles fine on its own.
 */
object TurnRouter {

    /** True if this utterance should go through the native tool-calling loop. */
    fun useNativeTools(text: String): Boolean {
        val t = text.lowercase()
        if (EXCLUDE.any { it.containsMatchIn(t) }) return false
        return INCLUDE.any { it.containsMatchIn(t) }
    }

    // Calendar, alarms/timers, screen control, and files — anything that needs a
    // tool ToolBox does not have. Checked first; wins over INCLUDE. Deliberately
    // does NOT list bare "call"/"text"/"message"/"send" — see class doc.
    private val EXCLUDE = listOf(
        Regex("""\b(calendar|event|meeting|appointment)\b"""),
        Regex("""\b(alarm|timer|wake me|wake up)\b"""),
        Regex("""\b(open|play|tap|checkout|type)\b"""),
        Regex("""\bsearch (for|in|on)\b"""),
        Regex("""\border\b.*\bon\b"""),
        Regex("""\b(file|pdf|document|photo|picture|screenshot)\b"""),
        // A named app is a near-unambiguous screen-control signal on its own.
        Regex("""\b(spotify|whatsapp|blinkit|zepto|zomato|swiggy|youtube|instagram|amazon( music)?|dominos|gmail)\b"""),
        Regex("""\b(remember|forget)\b"""), // memory: deliberately still the old path — see class doc
    )

    // Tasks, reminders, notes, and recall of past tasks/notes — exactly ToolBox's
    // wired-in tools (add_task, add_tasks, list_tasks, complete_task, delete_task,
    // set_reminder, list_reminders, save_note, search_my_stuff).
    private val INCLUDE = listOf(
        Regex("""\b(to-?do|task)s?\b"""),
        Regex("""\bremind(er|s)?\b"""),
        Regex("""\b(save|take|jot) (a |down )?note\b"""),
        Regex("""\bnote (that|down)\b"""),
        Regex("""\bmark\b.*\b(done|complete)\b"""),
        Regex("""\b(complete|finish(ed)?)\b.*\btask\b"""),
        Regex("""\bdelete\b.*\btask\b"""),
        Regex("""\bwhat did\b.*\b(say|agree|decide)\b"""),
        Regex("""\bwhat('?s| is| are)\b.*\b(on my (list|plate)|pending|outstanding)\b"""),
    )
}
