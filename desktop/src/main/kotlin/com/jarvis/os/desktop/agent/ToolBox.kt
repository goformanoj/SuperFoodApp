package com.jarvis.os.desktop.agent

import com.jarvis.os.desktop.brain.Brain
import com.jarvis.os.desktop.brain.Schedule
import com.jarvis.os.desktop.brain.TaskDates
import com.jarvis.os.desktop.knowledge.DocText
import com.jarvis.os.desktop.knowledge.FileSearch
import com.jarvis.os.desktop.knowledge.KnowledgeClient
import com.jarvis.os.desktop.knowledge.Library
import java.io.File
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
 *  - [Risk.SHARES]: sends something private off the laptop (a screenshot). It also never
 *    runs without the user's OK, and the card says it shares rather than destroys.
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
    enum class Risk {
        READ, UNDOABLE, IRREVERSIBLE, SHARES;
        /** True when the user must click Approve before the tool runs (Rule 6, in code). */
        val needsApproval: Boolean get() = this == IRREVERSIBLE || this == SHARES
    }

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

    /**
     * The machine and the outside world, as far as tools may touch them. The knowledge
     * members default to "not available", so a test host only fakes what it exercises.
     */
    interface Host {
        fun openUrl(url: String): Boolean
        fun openApp(name: String): String?     // the app actually opened, or null
        fun clipboardText(): String?
        /** Opens a document with its default app (never a program; the tool checks). */
        fun openFile(path: String): Boolean = false
        suspend fun webSearch(query: String): KnowledgeClient.WebAnswer = throw UnsupportedOperationException("Web search isn't available here.")
        suspend fun searchFiles(q: FileSearch.Query): List<FileSearch.Found> = throw UnsupportedOperationException("File search isn't available here.")
        /** One screenshot (JARVIS's own window out of the way) → the vision model's answer. */
        suspend fun askAboutScreen(question: String): String = throw UnsupportedOperationException("Looking at the screen isn't available here.")
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
            "add_tasks", "Add several to-dos at once, e.g. the action points from a document or a list the user gives. Use this instead of calling add_task repeatedly.",
            obj(
                "tasks" to JSONObject().put("type", "array").put("description", "The tasks to add").put(
                    "items", obj(
                        "title" to str("What to do, short and specific"),
                        "due" to str("Optional local date-time, ISO format YYYY-MM-DDTHH:MM (or YYYY-MM-DD)"),
                        "notes" to str("Optional extra detail"),
                        required = listOf("title"),
                    ),
                ),
                required = listOf("tasks"),
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
        // ── Routines (Phase 6, S9) ──
        Spec(
            "create_routine", "Set up something JARVIS does by itself on a schedule, e.g. \"every weekday at 8, brief me\". At that time the instruction is run with your tools and the result pops up (and is spoken).",
            obj(
                "name" to str("Short name, e.g. \"Morning brief\""),
                "instruction" to str("What to do each time, as a clear request to yourself, e.g. \"Brief me: today's tasks and reminders, and the weather in Pune\""),
                "days" to str("\"weekdays\", \"weekends\", \"daily\", or days like \"mon, thu\""),
                "time" to str("Local time, HH:MM (24-hour)"),
                required = listOf("name", "instruction", "days", "time"),
            ),
            Risk.UNDOABLE,
        ),
        Spec("list_routines", "List the user's routines and when each runs next.", obj(), Risk.READ),
        Spec(
            "delete_routine", "Stop and remove a routine. Identify it by (part of) its name.",
            obj("routine" to str("Name or part of the name"), required = listOf("routine")),
            Risk.UNDOABLE,
        ),
        // ── Knowledge (Phase 5, AGENT_PLAN §5) ──
        Spec(
            "web_search", "Search the live web. Use for news, prices, weather, scores, schedules or anything that may have changed since your training.",
            obj("query" to str("What to look up, as a search query"), required = listOf("query")),
            Risk.READ,
        ),
        Spec(
            "read_document", "Read a document: one attached to this chat or added before (by name), or a file path from search_files. Returns its text with page numbers. Long documents come as an even sample of every page; use search_documents for specific details.",
            obj(
                "document" to str("The document's name (or part of it), or a full file path"),
                "pages" to str("Optional page range to read in full, e.g. \"4\" or \"3-5\""),
                required = listOf("document"),
            ),
            Risk.READ,
        ),
        Spec(
            "search_documents", "Find the passages in the user's documents that answer a question. Every result carries its page, so cite it.",
            obj(
                "query" to str("The question or key words"),
                "document" to str("Optional: only this document (name or part of it)"),
                required = listOf("query"),
            ),
            Risk.READ,
        ),
        Spec(
            "search_files", "Find files on this laptop by name and content (Windows Search). For \"the invoice from March\" use query \"invoice\" with modified_after/modified_before for March.",
            obj(
                "query" to str("Words in the file's name or content"),
                "kind" to enumStr("Optional file category", *FileSearch.KINDS.toTypedArray()),
                "modified_after" to str("Optional date YYYY-MM-DD"),
                "modified_before" to str("Optional date YYYY-MM-DD"),
                required = listOf("query"),
            ),
            Risk.READ,
        ),
        Spec(
            "open_file", "Open a file (from search_files) in its default app, e.g. a PDF in the reader. Documents only, never programs.",
            obj("path" to str("The full file path"), required = listOf("path")),
            Risk.READ,
        ),
        Spec(
            "look_at_screen", "Look at the user's screen (one screenshot) to answer a question about what is on it: an error, a page, a form. The user approves each look.",
            obj("question" to str("What to find out from the screen"), required = listOf("question")),
            Risk.SHARES,
        ),
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
        "look_at_screen" -> "Look at your screen to answer: “${args.optString("question").take(120)}”"
        else -> name.replace('_', ' ')
    }

    /** The line under an approval card: what approving actually means. */
    fun approvalNote(name: String): String = when (spec(name)?.risk) {
        Risk.SHARES -> "JARVIS takes one screenshot of the screen you're on and sends it to its vision model for this question. It isn't saved."
        else -> "This can't be undone."
    }

    /**
     * Runs a tool. Callers MUST have checked [Spec.risk] first: an IRREVERSIBLE tool reaches
     * here only after the user approved it. Never throws — every failure becomes a result
     * the model is told about honestly.
     */
    suspend fun execute(name: String, rawArgs: String, sourceConversation: String?): Result { return try {
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
            "add_tasks" -> {
                val items = args.optJSONArray("tasks") ?: return fail("add_tasks needs a list of tasks.")
                if (items.length() == 0) return fail("The task list was empty.")
                if (items.length() > MAX_BATCH) return fail("That's more than $MAX_BATCH tasks at once. Add the most important ones first.")
                // Check every item before adding any, so a bad date never leaves half a list behind.
                val parsed = (0 until items.length()).map { i ->
                    val o = items.optJSONObject(i) ?: return fail("Task ${i + 1} isn't an object.")
                    val title = o.optString("title").trim()
                    if (title.isEmpty()) return fail("Task ${i + 1} has no title.")
                    val due = o.optString("due").takeIf { it.isNotBlank() }?.let { parseLocal(it) ?: return fail("I couldn't read the due date “${o.optString("due")}” for “$title”.") }
                    Triple(title, due, o.optString("notes").ifBlank { null })
                }
                val added = parsed.map { (title, due, notes) -> brain.addTask(title, dueAt = due, notes = notes, sourceConversation = sourceConversation) }
                brain.log("task", "Added ${added.size} tasks: " + added.joinToString("; ") { it.title })
                val arr = JSONArray()
                added.forEach { t -> arr.put(JSONObject().put("title", t.title).put("due", t.dueAt?.let { TaskDates.label(it, clock(), zone) })) }
                ok(JSONObject().put("added", arr).put("count", added.size),
                    "Added ${added.size} tasks: " + added.joinToString(", ") { "“${it.title}”" },
                    Undo("tasks", added.joinToString(",") { it.id }))
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
            "create_routine" -> {
                val name = args.optString("name").trim()
                val instruction = args.optString("instruction").trim()
                if (name.isEmpty() || instruction.isEmpty()) return fail("A routine needs a name and an instruction.")
                val schedule = Schedule.parse(args.optString("days"), args.optString("time"))
                    ?: return fail("I couldn't read the schedule “${args.optString("days")} at ${args.optString("time")}”.")
                val next = schedule.next(clock(), zone)
                val r = brain.addRoutine(name, instruction, schedule, next, sourceConversation = sourceConversation)
                brain.log("routine", "Routine set: ${r.name} · ${schedule.describe()}")
                ok(JSONObject().put("routine", r.name).put("when", schedule.describe()).put("next", TaskDates.label(next, clock(), zone)),
                    "Routine “${r.name}” · ${schedule.describe()} · next ${TaskDates.label(next, clock(), zone)}", Undo("routine", r.id))
            }
            "list_routines" -> {
                val arr = JSONArray()
                brain.routines().forEach { r ->
                    arr.put(JSONObject().put("name", r.name).put("when", r.schedule.describe()).put("instruction", r.instruction)
                        .put("enabled", r.enabled).put("next", if (r.enabled) TaskDates.label(r.nextRun, clock(), zone) else null))
                }
                ok(JSONObject().put("routines", arr), "Looked at your routines (${arr.length()})")
            }
            "delete_routine" -> {
                val q = args.optString("routine").trim()
                val matches = if (q.isEmpty()) emptyList() else brain.findRoutines(q)
                when {
                    matches.isEmpty() -> fail("No routine matches “$q”.")
                    matches.size > 1 && matches.none { it.name.equals(q, ignoreCase = true) } ->
                        fail("Several routines match “$q”: " + matches.joinToString("; ") { it.name } + ". Ask the user which one.")
                    else -> {
                        val r = matches.firstOrNull { it.name.equals(q, ignoreCase = true) } ?: matches.single()
                        brain.setRoutineDeleted(r.id, true)
                        brain.log("routine", "Routine removed: ${r.name}")
                        ok(JSONObject().put("removed", r.name), "Removed routine “${r.name}”", Undo("routine-restore", r.id))
                    }
                }
            }
            "web_search" -> {
                val q = args.optString("query").trim()
                if (q.isEmpty()) return fail("A web search needs something to look up.")
                val web = host.webSearch(q)
                if (web.answer.isBlank()) return fail("The web search came back empty.")
                brain.log("web", "Searched the web: $q")
                val src = JSONArray().apply { web.sources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } }
                ok(JSONObject().put("answer", web.answer).put("sources", src),
                    "Searched the web for “$q”" + if (web.sources.isNotEmpty()) " · " + web.sources.take(3).joinToString(", ") { hostOf(it.url) } else "")
            }
            "read_document" -> {
                val d = resolveDocument(args.optString("document"), sourceConversation) ?: return fail(
                    "No document matches “${args.optString("document")}”. Ask the user to attach it (the paperclip, or drag it onto JARVIS), or find it with search_files.",
                )
                val range = pageRange(args.optString("pages"), d.pages)
                val pages = brain.documentPages(d.id, range?.first ?: 1, range?.last ?: Int.MAX_VALUE).map { DocText.Page(it.first, it.second) }
                val (text, complete) = DocText.overview(pages, d.unit, budget = READ_BUDGET)
                ok(
                    JSONObject().put("document", d.name).put("unit", d.unit).put("total", d.pages).put("complete", complete).put("text", text)
                        .apply { if (!complete) put("note", "Long document: this is a sample of every ${d.unit}. Use search_documents for specific details.") },
                    "Read “${d.name}”" + (range?.let { " · ${d.unit}s ${it.first}–${it.last}" } ?: " (${d.pages} ${d.unit}${if (d.pages == 1) "" else "s"})"),
                )
            }
            "search_documents" -> {
                val q = args.optString("query").trim()
                val only = args.optString("document").trim().takeIf { it.isNotEmpty() }?.let {
                    resolveDocument(it, sourceConversation) ?: return fail("No document matches “$it”.")
                }
                val hits = brain.searchDocuments(q, only?.id, limit = 6)
                val arr = JSONArray()
                hits.forEach { h -> arr.put(JSONObject().put("document", h.docName).put("where", "${h.unit} ${h.page}").put("text", h.snippet.take(900))) }
                ok(JSONObject().put("passages", arr).apply { if (hits.isEmpty()) put("note", "Nothing matched. Try other words, or read_document for an overview.") },
                    "Looked through ${only?.let { "“${it.name}”" } ?: "your documents"} for “$q” (${hits.size} passage${if (hits.size == 1) "" else "s"})")
            }
            "search_files" -> {
                val q = FileSearch.query(args.optString("query"), args.optString("kind").ifBlank { null },
                    args.optString("modified_after").ifBlank { null }, args.optString("modified_before").ifBlank { null })
                    ?: return fail("Tell me what the file is called or what's in it.")
                val found = host.searchFiles(q).take(15)
                brain.log("files", "Searched the laptop: ${args.optString("query")}")
                val arr = JSONArray()
                found.forEach { f -> arr.put(JSONObject().put("name", f.name).put("path", f.path).put("modified", f.modified)) }
                ok(JSONObject().put("files", arr).apply { if (found.isEmpty()) put("note", "No files matched. Try fewer or different words, or no dates.") },
                    "Searched the laptop for “${args.optString("query")}” (${found.size} file${if (found.size == 1) "" else "s"})")
            }
            "open_file" -> {
                val f = File(args.optString("path").trim())
                if (!f.isFile) return fail("There's no file at that path.")
                if (f.extension.lowercase() in NEVER_OPEN) return fail("I only open documents, not programs or scripts. To start an app, use open_app.")
                if (!host.openFile(f.path)) return fail("Windows couldn't open “${f.name}”.")
                brain.log("open", "Opened ${f.name}", detail = f.path)
                ok(JSONObject().put("opened", f.name), "Opened “${f.name}”")
            }
            "look_at_screen" -> {
                val answer = host.askAboutScreen(args.optString("question").ifBlank { "What is on this screen?" })
                if (answer.isBlank()) return fail("I couldn't make out anything on the screen.")
                brain.log("screen", "Looked at the screen")
                ok(JSONObject().put("screen", answer), "Looked at your screen")
            }
            else -> fail("Unknown tool “$name”.")
        }
    } catch (e: Exception) {
        fail("That failed: ${e.message ?: e.javaClass.simpleName}")
    } }

    /** Reverses an undoable step. Returns a line for the card, or null if nothing was left to undo. */
    fun undo(u: Undo): String? = when (u.kind) {
        "task" -> brain.task(u.id)?.let { brain.deleteTask(u.id); brain.log("undo", "Removed task: ${it.title}"); "Removed task “${it.title}”" }
        "routine" -> brain.routine(u.id)?.let { brain.setRoutineDeleted(u.id, true); brain.log("undo", "Removed routine: ${it.name}"); "Removed the routine" }
        "routine-restore" -> { brain.setRoutineDeleted(u.id, false); brain.routine(u.id)?.let { brain.log("undo", "Restored routine: ${it.name}"); "Restored “${it.name}”" } }
        "tasks" -> u.id.split(',').mapNotNull { id -> brain.task(id)?.also { brain.deleteTask(id) } }
            .takeIf { it.isNotEmpty() }?.let { gone -> brain.log("undo", "Removed ${gone.size} tasks"); "Removed ${gone.size} tasks" }
        "reopen" -> brain.task(u.id)?.let { brain.setTaskDone(u.id, false); brain.log("undo", "Reopened: ${it.title}"); "Reopened “${it.title}”" }
        "reminder" -> { brain.deleteReminder(u.id); brain.log("undo", "Cancelled a reminder"); "Cancelled the reminder" }
        "note" -> { brain.deleteNote(u.id); brain.log("undo", "Deleted a note"); "Deleted the note" }
        "memory" -> { brain.deleteMemory(u.id); brain.log("undo", "Forgot a memory"); "Forgot it" }
        else -> null
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * "this PDF", "the lease", or a path. A path is read (and kept) if it's a readable
     * file; a name matches this chat's attachments first, then everything added before.
     */
    private suspend fun resolveDocument(ref: String, sourceConversation: String?): Brain.Document? {
        val r = ref.trim().trim('"', '“', '”')
        val attached = sourceConversation?.let { brain.attachedDocuments(it) }.orEmpty()
        if (r.isEmpty()) return attached.lastOrNull()
        if (r.contains(":\\") || r.contains(":/") || r.startsWith("\\\\")) {
            val f = File(r)
            if (f.isFile) return Library.import(brain, f, sourceConversation)
        }
        return attached.lastOrNull { it.name.contains(r, ignoreCase = true) || r.contains(it.name.substringBeforeLast('.'), ignoreCase = true) }
            ?: brain.findDocuments(r).firstOrNull()
            ?: brain.findDocuments(r.substringBeforeLast('.')).firstOrNull()
            // "this document" / "the pdf": the only thing attached here.
            ?: attached.singleOrNull()?.takeIf { isGenericRef(r) }
    }

    /** "4" → 4..4, "3-5" / "3–5" / "3 to 5" → 3..5, clamped to the document; blank or nonsense → null. */
    fun pageRange(s: String, total: Int): IntRange? {
        val m = Regex("""^\s*(\d+)\s*(?:(?:-|–|to)\s*(\d+))?\s*$""").find(s) ?: return null
        val a = m.groupValues[1].toInt().coerceIn(1, maxOf(1, total))
        val b = (m.groupValues[2].toIntOrNull() ?: a).coerceIn(a, maxOf(a, total))
        return a..b
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrNull() ?: url

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
        /** Most tasks one add_tasks call may create. */
        const val MAX_BATCH = 25
        /** Characters of document text handed to the model in one read (~3k tokens). */
        const val READ_BUDGET = 12_000
        /** Never opened by open_file, whatever the model asks: things that run code. */
        val NEVER_OPEN = setOf(
            "exe", "com", "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "msi", "msp", "scr",
            "pif", "lnk", "jar", "reg", "hta", "cpl", "dll", "sys", "inf", "application", "appref-ms", "url", "gadget",
        )
        private val GENERIC_WORDS = setOf("this", "that", "the", "it", "attached", "my", "one", "document", "doc", "pdf", "file", "word", "docx", "text", "report")
        /** "this document", "the attached pdf": words that point at the attachment rather than name one. */
        fun isGenericRef(ref: String): Boolean =
            ref.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() }.let { w -> w.isNotEmpty() && w.all { it in GENERIC_WORDS } }
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
