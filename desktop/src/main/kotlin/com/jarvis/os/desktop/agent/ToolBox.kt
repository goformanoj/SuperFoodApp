package com.jarvis.os.desktop.agent

import com.jarvis.os.desktop.brain.Brain
import com.jarvis.os.desktop.brain.TaskDates
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The desktop agent's tools (AGENT_PLAN §4): what the model may ask JARVIS to do on the
 * laptop, each with a JSON schema the model sees, a RISK LEVEL enforced here in code, and
 * a local executor against the brain.
 *
 * Risk is never left to the prompt (Rule 6 — prompts are probabilistic):
 *  - [Risk.READ]: runs immediately.
 *  - [Risk.UNDOABLE]: runs immediately; the result carries an [Undo] the user can click.
 *  - [Risk.IRREVERSIBLE]: never runs until the user approves it on an approval card.
 *
 * Side effects outside the brain (opening a URL or an app, reading the clipboard) go
 * through [Host] so tests can run every tool without touching the machine.
 */
class ToolBox(
    private val brain: Brain,
    private val host: Host,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    enum class Risk { READ, UNDOABLE, IRREVERSIBLE }

    /** What undoing a step means: delete what was created, or restore a task's state. */
    data class Undo(val kind: String, val id: String)

    data class Result(
        val ok: Boolean,
        /** JSON handed back to the model. */
        val forModel: String,
        /** One plain line for the step card in the chat. */
        val summary: String,
        val undo: Undo? = null,
    )

    /** The machine, as far as tools may touch it. */
    interface Host {
        fun openUrl(url: String): Boolean
        fun openApp(name: String): String?     // the app actually opened, or null
        fun clipboardText(): String?
    }

    class Spec(val name: String, val description: String, val params: JSONObject, val risk: Risk)

    val specs: List<Spec> = listOf(
        Spec(
            "add_task", "Add a to-do for the user. Use due for a deadline or when they say when.",
            obj(
                "title" to str("What to do, short and specific"),
                "due" to str("Optional local date-time, ISO format YYYY-MM-DDTHH:MM (or YYYY-MM-DD)"),
                "notes" to str("Optional extra detail"),
                required = listOf("title"),
            ),
            Risk.UNDOABLE,
        ),
        Spec(
            "list_tasks", "List the user's tasks.",
            obj("which" to enumStr("today = due today or overdue; open = all not done; done = recently completed", "today", "open", "done"), required = listOf("which")),
            Risk.READ,
        ),
        Spec(
            "complete_task", "Mark a task done. Identify it by (part of) its title.",
            obj("task" to str("Title or part of the title"), required = listOf("task")),
            Risk.UNDOABLE,
        ),
        Spec(
            "delete_task", "Delete a task for good (only when the user explicitly asks to delete, not complete).",
            obj("task" to str("Title or part of the title"), required = listOf("task")),
            Risk.IRREVERSIBLE,
        ),
        Spec(
            "set_reminder", "Remind the user at a time: a notification pops up on the laptop.",
            obj(
                "text" to str("What to remind them about"),
                "at" to str("Local date-time, ISO format YYYY-MM-DDTHH:MM"),
                required = listOf("text", "at"),
            ),
            Risk.UNDOABLE,
        ),
        Spec("list_reminders", "List upcoming reminders.", obj(), Risk.READ),
        Spec(
            "save_note", "Save a note the user wants kept (a summary, a draft, a list).",
            obj("title" to str("Short title"), "body" to str("The note's text"), required = listOf("title", "body")),
            Risk.UNDOABLE,
        ),
        Spec(
            "search_my_stuff", "Search the user's own chats, tasks, notes and memory. Use before answering questions about what they said or decided before.",
            obj("query" to str("Words to look for"), required = listOf("query")),
            Risk.READ,
        ),
        Spec(
            "remember", "Remember a durable fact about the user (never passwords, PINs, OTPs or card numbers).",
            obj(
                "fact" to str("The fact, as one short sentence"),
                "kind" to enumStr("What sort of fact", "profile", "person", "place", "project", "instruction", "fact"),
                required = listOf("fact"),
            ),
            Risk.UNDOABLE,
        ),
        Spec(
            "forget", "Forget every remembered fact that mentions something.",
            obj("about" to str("The word or name to forget"), required = listOf("about")),
            Risk.IRREVERSIBLE,
        ),
        Spec(
            "open_url", "Open a web page in the user's browser.",
            obj("url" to str("Full http(s) URL"), required = listOf("url")),
            Risk.READ,
        ),
        Spec(
            "open_app", "Open an installed app on the laptop by name (e.g. Notepad, Calculator, Chrome, Word).",
            obj("name" to str("The app's name"), required = listOf("name")),
            Risk.READ,
        ),
        Spec("read_clipboard", "Read the text the user has copied, to act on it.", obj(), Risk.READ),
    )

    fun spec(name: String): Spec? = specs.firstOrNull { it.name == name }

    /** The list sent to the Worker (OpenAI function-tool format). */
    fun schemas(): JSONArray = JSONArray().apply {
        specs.forEach { s ->
            put(JSONObject().put("type", "function").put("function", JSONObject().put("name", s.name).put("description", s.description).put("parameters", s.params)))
        }
    }

    /** A short human description of a pending step, for the approval card. */
    fun describe(name: String, args: JSONObject): String = when (name) {
        "delete_task" -> "Delete the task matching “${args.optString("task")}”"
        "forget" -> "Forget everything remembered about “${args.optString("about")}”"
        else -> name.replace('_', ' ')
    }

    /**
     * Runs a tool. Callers MUST have checked [Spec.risk] first: an IRREVERSIBLE tool reaches
     * here only after the user approved it. Never throws — every failure becomes a result
     * the model is told about honestly.
     */
    fun execute(name: String, rawArgs: String, sourceConversation: String?): Result { return try {
        val args = runCatching { JSONObject(rawArgs.ifBlank { "{}" }) }.getOrElse { return fail("The arguments were not valid JSON.") }
        when (name) {
            "add_task" -> {
                val title = args.optString("title").trim()
                if (title.isEmpty()) return fail("A task needs a title.")
                val due = args.optString("due").takeIf { it.isNotBlank() }?.let { parseLocal(it) ?: return fail("I couldn't read the due date “${args.optString("due")}”.") }
                val t = brain.addTask(title, dueAt = due, notes = args.optString("notes").ifBlank { null }, sourceConversation = sourceConversation)
                brain.log("task", "Added task: ${t.title}")
                ok(JSONObject().put("added", t.title).put("due", due?.let { TaskDates.label(it, clock(), zone) }),
                    "Added task “${t.title}”" + (due?.let { " · ${TaskDates.label(it, clock(), zone)}" } ?: ""), Undo("task", t.id))
            }
            "list_tasks" -> {
                val now = clock()
                val tasks = when (args.optString("which", "open")) {
                    "today" -> brain.tasksDueBy(TaskDates.endOfDay(now, zone))
                    "done" -> brain.doneTasks(15)
                    else -> brain.openTasks()
                }
                val arr = JSONArray()
                tasks.take(30).forEach { t -> arr.put(JSONObject().put("title", t.title).put("due", t.dueAt?.let { TaskDates.label(it, now, zone) }).put("done", t.status == Brain.TaskStatus.DONE)) }
                ok(JSONObject().put("count", tasks.size).put("tasks", arr), "Looked at your tasks (${tasks.size})")
            }
            "complete_task", "delete_task" -> {
                val q = args.optString("task").trim()
                val matches = findTasks(q, includeDone = false)
                when {
                    matches.isEmpty() -> fail("No open task matches “$q”.")
                    matches.size > 1 && matches.none { it.title.equals(q, ignoreCase = true) } ->
                        fail("Several tasks match “$q”: " + matches.take(5).joinToString("; ") { it.title } + ". Ask the user which one.")
                    else -> {
                        val t = matches.firstOrNull { it.title.equals(q, ignoreCase = true) } ?: matches.single()
                        if (name == "complete_task") {
                            brain.setTaskDone(t.id, true)
                            brain.log("task", "Completed: ${t.title}")
                            ok(JSONObject().put("completed", t.title), "Completed “${t.title}”", Undo("reopen", t.id))
                        } else {
                            brain.deleteTask(t.id)
                            brain.log("task", "Deleted: ${t.title}")
                            ok(JSONObject().put("deleted", t.title), "Deleted task “${t.title}”")
                        }
                    }
                }
            }
            "set_reminder" -> {
                val text = args.optString("text").trim()
                if (text.isEmpty()) return fail("A reminder needs something to remind about.")
                val at = parseLocal(args.optString("at")) ?: return fail("I need a time for the reminder (got “${args.optString("at")}”).")
                if (at < clock() - 60_000) return fail("That time has already passed.")
                val r = brain.addReminder(text, at)
                brain.log("reminder", "Reminder set: $text · ${TaskDates.label(at, clock(), zone)}")
                ok(JSONObject().put("reminder", text).put("at", TaskDates.label(at, clock(), zone)),
                    "Reminder “$text” · ${TaskDates.label(at, clock(), zone)}", Undo("reminder", r.id))
            }
            "list_reminders" -> {
                val now = clock()
                val arr = JSONArray()
                brain.upcomingReminders().take(30).forEach { arr.put(JSONObject().put("text", it.text).put("at", TaskDates.label(it.at, now, zone))) }
                ok(JSONObject().put("reminders", arr), "Looked at your reminders (${arr.length()})")
            }
            "save_note" -> {
                val n = brain.addNote(args.optString("title"), args.optString("body"), sourceConversation = sourceConversation)
                brain.log("note", "Saved note: ${n.title}")
                ok(JSONObject().put("saved", n.title), "Saved note “${n.title}”", Undo("note", n.id))
            }
            "search_my_stuff" -> {
                val hits = brain.search(args.optString("query"), limit = 10)
                val arr = JSONArray()
                hits.forEach { h -> arr.put(JSONObject().put("kind", h.kind).put("title", h.title).put("excerpt", h.snippet.replace("[", "").replace("]", ""))) }
                ok(JSONObject().put("results", arr), "Searched your things for “${args.optString("query")}” (${hits.size} found)")
            }
            "remember" -> {
                val fact = args.optString("fact").trim()
                if (looksSecret(fact)) return fail("That looks like a secret (a code, PIN, password or card number) — I won't store it.")
                val kind = runCatching { Brain.MemoryKind.valueOf(args.optString("kind", "fact").uppercase()) }.getOrDefault(Brain.MemoryKind.FACT)
                val m = brain.remember(fact, kind, sourceConversation) ?: return ok(JSONObject().put("already_known", fact), "Already remembered “$fact”")
                brain.log("memory", "Remembered: ${m.text}")
                ok(JSONObject().put("remembered", m.text), "Remembered “${m.text}”", Undo("memory", m.id))
            }
            "forget" -> {
                val about = args.optString("about").trim()
                val n = brain.forget(about)
                if (n > 0) brain.log("memory", "Forgot $n item(s) about “$about”")
                ok(JSONObject().put("forgotten", n), if (n > 0) "Forgot $n item(s) about “$about”" else "Nothing remembered about “$about”")
            }
            "open_url" -> {
                val url = args.optString("url").trim()
                if (!(url.startsWith("https://") || url.startsWith("http://"))) return fail("Only web addresses (http/https) can be opened.")
                if (!host.openUrl(url)) return fail("The browser didn't open.")
                brain.log("open", "Opened $url")
                ok(JSONObject().put("opened", url), "Opened $url")
            }
            "open_app" -> {
                val app = args.optString("name").trim()
                val opened = host.openApp(app) ?: return fail("I couldn't find an app called “$app” on this laptop.")
                brain.log("open", "Opened $opened")
                ok(JSONObject().put("opened", opened), "Opened $opened")
            }
            "read_clipboard" -> {
                val text = host.clipboardText()?.take(8_000)
                if (text.isNullOrBlank()) fail("The clipboard has no text.")
                else ok(JSONObject().put("clipboard", text), "Read the clipboard (${text.length} characters)")
            }
            else -> fail("Unknown tool “$name”.")
        }
    } catch (e: Exception) {
        fail("That failed: ${e.message ?: e.javaClass.simpleName}")
    } }

    /** Reverses an undoable step. Returns a line for the card, or null if nothing was left to undo. */
    fun undo(u: Undo): String? = when (u.kind) {
        "task" -> brain.task(u.id)?.let { brain.deleteTask(u.id); brain.log("undo", "Removed task: ${it.title}"); "Removed task “${it.title}”" }
        "reopen" -> brain.task(u.id)?.let { brain.setTaskDone(u.id, false); brain.log("undo", "Reopened: ${it.title}"); "Reopened “${it.title}”" }
        "reminder" -> { brain.deleteReminder(u.id); brain.log("undo", "Cancelled a reminder"); "Cancelled the reminder" }
        "note" -> { brain.deleteNote(u.id); brain.log("undo", "Deleted a note"); "Deleted the note" }
        "memory" -> { brain.deleteMemory(u.id); brain.log("undo", "Forgot a memory"); "Forgot it" }
        else -> null
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun findTasks(q: String, includeDone: Boolean): List<Brain.Task> {
        if (q.isBlank()) return emptyList()
        val pool = brain.openTasks() + if (includeDone) brain.doneTasks(50) else emptyList()
        return pool.filter { it.title.contains(q, ignoreCase = true) || q.contains(it.title, ignoreCase = true) }
    }

    /** "2026-09-29T17:00", "2026-09-29 17:00", "2026-09-29T17:00:00" or a bare date (→ 09:00). */
    fun parseLocal(s: String): Long? {
        val t = s.trim().replace(' ', 'T')
        if (t.isEmpty()) return null
        return try {
            LocalDateTime.parse(if (t.length == 16) "$t:00" else t.take(19)).atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try { LocalDate.parse(t.take(10)).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() } catch (e2: DateTimeParseException) { null }
        }
    }

    private fun ok(forModel: JSONObject, summary: String, undo: Undo? = null) = Result(true, forModel.put("ok", true).toString(), summary, undo)
    private fun fail(why: String) = Result(false, JSONObject().put("ok", false).put("error", why).toString(), why)

    companion object {
        private val SECRET = Regex("""(?i)\b(pin|otp|password|passcode|cvv)\b|\b\d{6}\b|\b(?:\d[ -]?){13,19}\b""")
        fun looksSecret(text: String) = SECRET.containsMatchIn(text)

        private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
        private fun enumStr(desc: String, vararg values: String) = str(desc).put("enum", JSONArray(values.toList()))
        private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
            val p = JSONObject()
            props.forEach { (k, v) -> p.put(k, v) }
            return JSONObject().put("type", "object").put("properties", p).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
        }
    }
}
