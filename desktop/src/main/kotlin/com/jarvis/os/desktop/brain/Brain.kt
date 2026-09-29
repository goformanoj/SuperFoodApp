package com.jarvis.os.desktop.brain

import org.json.JSONObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * JARVIS's brain on the laptop — AGENT_PLAN §3: everything JARVIS knows or makes, in one
 * local SQLite file with full-text search, instead of loose JSON. Local-first: it works
 * offline, it is fast, the user owns it. (Sync through the Worker is Phase 7.)
 *
 * Entities: projects, conversations + messages, typed memory with provenance, tasks,
 * reminders, notes, and an activity log of everything JARVIS did. One FTS5 index covers
 * conversations, messages, memory, tasks and notes, so a single search box (and the
 * agent's `search_my_stuff` tool) can find anything.
 *
 * IDs are random UUID strings, not autoincrement numbers, so rows made on the phone and
 * the laptop can later sync without colliding. Times are epoch milliseconds.
 *
 * All of it is plain JDBC and unit-tested against a temporary database (BrainTest).
 * Not thread-safe by itself: callers go through one thread (the UI's) or synchronise.
 */
class Brain private constructor(private val db: Connection, private val clock: () -> Long) : AutoCloseable {

    // ── Model ────────────────────────────────────────────────────────────────

    data class Project(val id: String, val name: String, val color: String?, val pinned: Boolean, val updated: Long)
    data class Conversation(
        val id: String, val title: String, val projectId: String?, val pinned: Boolean,
        val archived: Boolean, val created: Long, val updated: Long,
    )
    data class Message(val id: String, val conversationId: String, val role: String, val content: String, val created: Long)

    /** Typed memory — AGENT_PLAN §3: profile, person, place, project context, instruction, or a plain fact. */
    enum class MemoryKind { PROFILE, PERSON, PLACE, PROJECT, INSTRUCTION, FACT }
    data class Memory(val id: String, val kind: MemoryKind, val text: String, val sourceConversation: String?, val created: Long)

    enum class TaskStatus { OPEN, DONE }
    data class Task(
        val id: String, val title: String, val notes: String?, val dueAt: Long?, val priority: Int,
        val status: TaskStatus, val projectId: String?, val sourceConversation: String?,
        val created: Long, val completedAt: Long?,
    )
    data class Reminder(val id: String, val text: String, val at: Long, val recurrence: String?, val delivered: Boolean, val taskId: String?)
    data class Note(val id: String, val title: String, val body: String, val projectId: String?, val sourceConversation: String?, val updated: Long)
    data class Activity(val id: String, val at: Long, val kind: String, val summary: String, val detail: String?)

    data class SearchHit(val kind: String, val refId: String, val title: String, val snippet: String)

    // ── Projects ─────────────────────────────────────────────────────────────

    fun createProject(name: String, color: String? = null): Project {
        val clean = name.trim().ifEmpty { throw IllegalArgumentException("project name is blank") }
        val p = Project(newId(), clean, color, false, clock())
        exec("INSERT INTO projects(id,name,color,pinned,created,updated) VALUES(?,?,?,?,?,?)", p.id, p.name, p.color, 0, p.updated, p.updated)
        return p
    }

    fun projects(): List<Project> = query("SELECT * FROM projects ORDER BY pinned DESC, name COLLATE NOCASE") {
        Project(it.getString("id"), it.getString("name"), it.getString("color"), it.getInt("pinned") == 1, it.getLong("updated"))
    }

    fun renameProject(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        exec("UPDATE projects SET name=?, updated=? WHERE id=?", clean, clock(), id)
    }

    /** Deleting a project never deletes its contents: they become ungrouped. */
    fun deleteProject(id: String) = tx {
        exec("UPDATE conversations SET project_id=NULL WHERE project_id=?", id)
        exec("UPDATE tasks SET project_id=NULL WHERE project_id=?", id)
        exec("UPDATE notes SET project_id=NULL WHERE project_id=?", id)
        exec("DELETE FROM projects WHERE id=?", id)
    }

    // ── Conversations + messages ─────────────────────────────────────────────

    fun createConversation(title: String, projectId: String? = null, id: String = newId()): Conversation {
        val now = clock()
        exec(
            "INSERT INTO conversations(id,title,project_id,pinned,archived,created,updated) VALUES(?,?,?,0,0,?,?)",
            id, title, projectId, now, now,
        )
        index("conversation", id, title, "")
        return Conversation(id, title, projectId, false, false, now, now)
    }

    fun conversation(id: String): Conversation? =
        query("SELECT * FROM conversations WHERE id=?", id) { conv(it) }.firstOrNull()

    /** Pinned first, then most recently active. Archived ones only when asked. */
    fun conversations(includeArchived: Boolean = false): List<Conversation> = query(
        "SELECT * FROM conversations " + (if (includeArchived) "" else "WHERE archived=0 ") +
            "ORDER BY pinned DESC, updated DESC",
    ) { conv(it) }

    fun renameConversation(id: String, title: String) {
        exec("UPDATE conversations SET title=? WHERE id=?", title, id)
        index("conversation", id, title, "")
    }

    fun setPinned(id: String, pinned: Boolean) = exec("UPDATE conversations SET pinned=? WHERE id=?", if (pinned) 1 else 0, id)
    fun setArchived(id: String, archived: Boolean) = exec("UPDATE conversations SET archived=? WHERE id=?", if (archived) 1 else 0, id)
    fun moveToProject(conversationId: String, projectId: String?) = exec("UPDATE conversations SET project_id=? WHERE id=?", projectId, conversationId)

    fun deleteConversation(id: String) = tx {
        exec("DELETE FROM search_fts WHERE owner=?", id)
        exec("DELETE FROM messages WHERE conversation_id=?", id)
        exec("DELETE FROM conversation_docs WHERE conversation_id=?", id)
        exec("DELETE FROM conversations WHERE id=?", id)
        unindex(id)
    }

    fun addMessage(conversationId: String, role: String, content: String, at: Long = clock()): Message {
        val m = Message(newId(), conversationId, role, content, at)
        tx {
            exec("INSERT INTO messages(id,conversation_id,role,content,created) VALUES(?,?,?,?,?)", m.id, conversationId, role, content, at)
            exec("UPDATE conversations SET updated=? WHERE id=?", at, conversationId)
            index("message", m.id, "", content, owner = conversationId)
        }
        return m
    }

    /** Rewrites a message's content (an action card marked undone). */
    fun updateMessage(id: String, content: String) = exec("UPDATE messages SET content=? WHERE id=?", content, id)

    fun messages(conversationId: String): List<Message> =
        query("SELECT * FROM messages WHERE conversation_id=? ORDER BY created, rowid", conversationId) {
            Message(it.getString("id"), it.getString("conversation_id"), it.getString("role"), it.getString("content"), it.getLong("created"))
        }

    // ── Memory ───────────────────────────────────────────────────────────────

    /** Adds a memory; returns null if the same text is already remembered (case-insensitive). */
    fun remember(text: String, kind: MemoryKind = MemoryKind.FACT, sourceConversation: String? = null): Memory? {
        val clean = text.trim().take(MAX_MEMORY)
        if (clean.isEmpty()) return null
        val exists = query("SELECT 1 FROM memories WHERE lower(text)=lower(?)", clean) { true }.isNotEmpty()
        if (exists) return null
        val m = Memory(newId(), kind, clean, sourceConversation, clock())
        exec("INSERT INTO memories(id,kind,text,source_conversation,created) VALUES(?,?,?,?,?)", m.id, kind.name, clean, sourceConversation, m.created)
        index("memory", m.id, kind.name.lowercase(), clean)
        touch("memory", m.id)
        return m
    }

    /** The phone's FORGET semantics: every memory mentioning [about] goes. Returns how many. */
    fun forget(about: String): Int {
        val needle = about.trim()
        if (needle.isEmpty()) return 0
        val ids = query("SELECT id FROM memories WHERE instr(lower(text), lower(?)) > 0", needle) { it.getString(1) }
        ids.forEach { deleteMemory(it) }
        return ids.size
    }

    fun deleteMemory(id: String) {
        exec("DELETE FROM memories WHERE id=?", id)
        unindex(id)
        touch("memory", id, deleted = true)
    }

    fun memories(kind: MemoryKind? = null): List<Memory> = if (kind == null) {
        query("SELECT * FROM memories ORDER BY created") { mem(it) }
    } else {
        query("SELECT * FROM memories WHERE kind=? ORDER BY created", kind.name) { mem(it) }
    }

    fun memory(id: String): Memory? = query("SELECT * FROM memories WHERE id=?", id) { mem(it) }.firstOrNull()

    // ── Tasks ────────────────────────────────────────────────────────────────

    fun addTask(
        title: String, dueAt: Long? = null, notes: String? = null, priority: Int = 0,
        projectId: String? = null, sourceConversation: String? = null,
    ): Task {
        val clean = title.trim().ifEmpty { throw IllegalArgumentException("task title is blank") }
        val t = Task(newId(), clean, notes?.trim()?.ifEmpty { null }, dueAt, priority.coerceIn(0, 3), TaskStatus.OPEN, projectId, sourceConversation, clock(), null)
        exec(
            "INSERT INTO tasks(id,title,notes,due_at,priority,status,project_id,source_conversation,created,updated) VALUES(?,?,?,?,?,?,?,?,?,?)",
            t.id, t.title, t.notes, t.dueAt, t.priority, t.status.name, projectId, sourceConversation, t.created, t.created,
        )
        index("task", t.id, t.title, t.notes.orEmpty())
        touch("task", t.id)
        return t
    }

    fun task(id: String): Task? = query("SELECT * FROM tasks WHERE id=?", id) { task(it) }.firstOrNull()

    fun setTaskDone(id: String, done: Boolean) {
        val now = clock()
        exec("UPDATE tasks SET status=?, completed_at=?, updated=? WHERE id=?", if (done) "DONE" else "OPEN", if (done) now else null, now, id)
        touch("task", id)
    }

    fun updateTask(id: String, title: String? = null, dueAt: Long? = null, clearDue: Boolean = false, notes: String? = null) {
        val t = task(id) ?: return
        val newTitle = title?.trim()?.ifEmpty { null } ?: t.title
        val newDue = if (clearDue) null else dueAt ?: t.dueAt
        val newNotes = notes ?: t.notes
        exec("UPDATE tasks SET title=?, due_at=?, notes=?, updated=? WHERE id=?", newTitle, newDue, newNotes, clock(), id)
        index("task", id, newTitle, newNotes.orEmpty())
        touch("task", id)
    }

    fun deleteTask(id: String) {
        exec("DELETE FROM tasks WHERE id=?", id)
        unindex(id)
        touch("task", id, deleted = true)
    }

    /** Open tasks: overdue and dated first (soonest first), then undated by priority and age. */
    fun openTasks(): List<Task> = query(
        "SELECT * FROM tasks WHERE status='OPEN' ORDER BY due_at IS NULL, due_at, priority DESC, created",
    ) { task(it) }

    /** Open tasks due before [endMs] (i.e. today and anything overdue). */
    fun tasksDueBy(endMs: Long): List<Task> = query(
        "SELECT * FROM tasks WHERE status='OPEN' AND due_at IS NOT NULL AND due_at < ? ORDER BY due_at", endMs,
    ) { task(it) }

    fun doneTasks(limit: Int = 50): List<Task> = query(
        "SELECT * FROM tasks WHERE status='DONE' ORDER BY completed_at DESC LIMIT ?", limit,
    ) { task(it) }

    // ── Reminders ────────────────────────────────────────────────────────────

    fun addReminder(text: String, at: Long, recurrence: String? = null, taskId: String? = null): Reminder {
        val r = Reminder(newId(), text.trim(), at, recurrence, false, taskId)
        exec("INSERT INTO reminders(id,text,at,recurrence,delivered,task_id,created) VALUES(?,?,?,?,0,?,?)", r.id, r.text, at, recurrence, taskId, clock())
        touch("reminder", r.id)
        return r
    }

    fun reminder(id: String): Reminder? = query("SELECT * FROM reminders WHERE id=?", id) { rem(it) }.firstOrNull()

    /** Undelivered reminders whose time has come — what the notifier fires. */
    fun dueReminders(now: Long = clock()): List<Reminder> =
        query("SELECT * FROM reminders WHERE delivered=0 AND at<=? ORDER BY at", now) { rem(it) }

    fun upcomingReminders(): List<Reminder> =
        query("SELECT * FROM reminders WHERE delivered=0 ORDER BY at") { rem(it) }

    // Delivery is deliberately NOT synced: it is each device's own record of whether IT has
    // notified the user, so the same reminder still notifies once on the phone and once on
    // the laptop, rather than one device's notification silencing the other's.
    fun markDelivered(id: String) = exec("UPDATE reminders SET delivered=1 WHERE id=?", id)

    fun deleteReminder(id: String) {
        exec("DELETE FROM reminders WHERE id=?", id)
        touch("reminder", id, deleted = true)
    }

    // ── Notes ────────────────────────────────────────────────────────────────

    fun addNote(title: String, body: String, projectId: String? = null, sourceConversation: String? = null): Note {
        val now = clock()
        val n = Note(newId(), title.trim().ifEmpty { "Untitled note" }, body, projectId, sourceConversation, now)
        exec("INSERT INTO notes(id,title,body,project_id,source_conversation,created,updated) VALUES(?,?,?,?,?,?,?)", n.id, n.title, body, projectId, sourceConversation, now, now)
        index("note", n.id, n.title, body)
        touch("note", n.id)
        return n
    }

    fun notes(): List<Note> = query("SELECT * FROM notes ORDER BY updated DESC") { noteRow(it) }
    fun note(id: String): Note? = query("SELECT * FROM notes WHERE id=?", id) { noteRow(it) }.firstOrNull()

    fun deleteNote(id: String) {
        exec("DELETE FROM notes WHERE id=?", id)
        unindex(id)
        touch("note", id, deleted = true)
    }

    // ── Documents (AGENT_PLAN §5, S4) ────────────────────────────────────────

    /**
     * A file the user gave JARVIS: its text extracted locally, kept per page ([unit] is
     * "page" for PDFs, "part" for page-less files) and chunked into [doc_fts] so a question
     * pulls only the passages it needs. [path] + [modified] let a re-added file be
     * recognised instead of stored twice.
     */
    data class Document(
        val id: String, val name: String, val path: String?, val kind: String, val unit: String,
        val pages: Int, val chars: Int, val modified: Long?, val sourceConversation: String?, val created: Long,
    )
    data class DocHit(val docId: String, val docName: String, val unit: String, val page: Int, val snippet: String)

    fun addDocument(
        name: String, kind: String, unit: String, pages: List<Pair<Int, String>>, chunks: List<Pair<Int, String>>,
        path: String? = null, modified: Long? = null, sourceConversation: String? = null,
    ): Document {
        val d = Document(newId(), name, path, kind, unit, pages.size, pages.sumOf { it.second.length }, modified, sourceConversation, clock())
        tx {
            exec(
                "INSERT INTO documents(id,name,path,kind,unit,pages,chars,modified,source_conversation,created) VALUES(?,?,?,?,?,?,?,?,?,?)",
                d.id, d.name, d.path, d.kind, d.unit, d.pages, d.chars, d.modified, d.sourceConversation, d.created,
            )
            pages.forEach { (n, text) -> exec("INSERT INTO doc_pages(doc_id,page,text) VALUES(?,?,?)", d.id, n, text) }
            chunks.forEach { (n, text) -> exec("INSERT INTO doc_fts(doc_id,page,text) VALUES(?,?,?)", d.id, n, text) }
            // The name is findable from the one search box too.
            index("document", d.id, d.name, "")
        }
        return d
    }

    fun document(id: String): Document? = query("SELECT * FROM documents WHERE id=?", id) { doc(it) }.firstOrNull()
    fun documents(): List<Document> = query("SELECT * FROM documents ORDER BY created DESC") { doc(it) }

    /** The stored copy of the file at [path], if it hasn't changed since ([modified]). */
    fun documentFor(path: String, modified: Long): Document? =
        query("SELECT * FROM documents WHERE path=? AND modified=? ORDER BY created DESC LIMIT 1", path, modified) { doc(it) }.firstOrNull()

    /** Documents whose name contains [q] (case-insensitive), newest first. */
    fun findDocuments(q: String): List<Document> {
        val needle = q.trim()
        if (needle.isEmpty()) return emptyList()
        return query("SELECT * FROM documents WHERE instr(lower(name), lower(?)) > 0 ORDER BY created DESC", needle) { doc(it) }
    }

    /** Pages [from]..[to] of a document, in order. */
    fun documentPages(id: String, from: Int = 1, to: Int = Int.MAX_VALUE): List<Pair<Int, String>> =
        query("SELECT page, text FROM doc_pages WHERE doc_id=? AND page BETWEEN ? AND ? ORDER BY page", id, from, to) { it.getInt(1) to it.getString(2) }

    /** The best passages for [text], optionally within one document. Every hit carries its page. */
    fun searchDocuments(text: String, docId: String? = null, limit: Int = 6): List<DocHit> {
        val q = ftsQuery(text, all = false) ?: return emptyList()
        val sql = "SELECT f.doc_id, d.name, d.unit, f.page, f.text FROM doc_fts f JOIN documents d ON d.id = f.doc_id " +
            "WHERE doc_fts MATCH ?" + (if (docId != null) " AND f.doc_id = ?" else "") + " ORDER BY rank LIMIT ?"
        val args = if (docId != null) arrayOf<Any?>(q, docId, limit) else arrayOf<Any?>(q, limit)
        return query(sql, *args) { DocHit(it.getString(1), it.getString(2), it.getString(3), it.getInt(4), it.getString(5)) }
    }

    fun deleteDocument(id: String) = tx {
        exec("DELETE FROM doc_fts WHERE doc_id=?", id)
        exec("DELETE FROM doc_pages WHERE doc_id=?", id)
        exec("DELETE FROM conversation_docs WHERE doc_id=?", id)
        exec("DELETE FROM documents WHERE id=?", id)
        unindex(id)
    }

    /** Links a document to a chat ("attached here"), so the agent knows what "this PDF" is. */
    fun attachDocument(conversationId: String, docId: String) =
        exec("INSERT OR IGNORE INTO conversation_docs(conversation_id,doc_id,created) VALUES(?,?,?)", conversationId, docId, clock())

    fun attachedDocuments(conversationId: String): List<Document> = query(
        "SELECT d.* FROM documents d JOIN conversation_docs c ON c.doc_id = d.id WHERE c.conversation_id=? ORDER BY c.created", conversationId,
    ) { doc(it) }

    // ── Routines (AGENT_PLAN S9) ─────────────────────────────────────────────

    /**
     * Something JARVIS does by itself on a schedule ("every weekday at 8, brief me"): at
     * [nextRun] the agent runs [instruction] with its tools, and the result arrives as a
     * notification (spoken too when [speak]). Deleting is soft ([deleted]) so it can be undone.
     */
    data class Routine(
        val id: String, val name: String, val instruction: String, val schedule: Schedule, val nextRun: Long,
        val enabled: Boolean, val speak: Boolean, val lastRun: Long?, val sourceConversation: String?, val created: Long,
    )

    fun addRoutine(name: String, instruction: String, schedule: Schedule, nextRun: Long, speak: Boolean = true, sourceConversation: String? = null): Routine {
        val r = Routine(newId(), name.trim().ifEmpty { "Routine" }, instruction.trim(), schedule, nextRun, true, speak, null, sourceConversation, clock())
        exec(
            "INSERT INTO routines(id,name,instruction,schedule,next_run,enabled,speak,deleted,last_run,source_conversation,created) VALUES(?,?,?,?,?,1,?,0,NULL,?,?)",
            r.id, r.name, r.instruction, schedule.encode(), nextRun, if (speak) 1 else 0, sourceConversation, r.created,
        )
        return r
    }

    fun routine(id: String): Routine? = query("SELECT * FROM routines WHERE id=? AND deleted=0", id) { routine(it) }.firstOrNull()
    /** Every live routine, soonest first. */
    fun routines(): List<Routine> = query("SELECT * FROM routines WHERE deleted=0 ORDER BY enabled DESC, next_run") { routine(it) }.filterNotNull()
    /** Enabled routines whose time has come. */
    fun dueRoutines(now: Long = clock()): List<Routine> =
        query("SELECT * FROM routines WHERE deleted=0 AND enabled=1 AND next_run<=? ORDER BY next_run", now) { routine(it) }.filterNotNull()

    fun setRoutineEnabled(id: String, enabled: Boolean, nextRun: Long? = null) =
        exec("UPDATE routines SET enabled=?, next_run=COALESCE(?, next_run) WHERE id=?", if (enabled) 1 else 0, nextRun, id)
    fun setRoutineSpeak(id: String, speak: Boolean) = exec("UPDATE routines SET speak=? WHERE id=?", if (speak) 1 else 0, id)
    fun setRoutineDeleted(id: String, deleted: Boolean) = exec("UPDATE routines SET deleted=? WHERE id=?", if (deleted) 1 else 0, id)
    /** Records a run (or a skip, with [ranAt] null) and moves the routine to its next time. */
    fun markRoutine(id: String, ranAt: Long?, nextRun: Long) =
        exec("UPDATE routines SET last_run=COALESCE(?, last_run), next_run=? WHERE id=?", ranAt, nextRun, id)

    /** Routines whose name contains [q] (case-insensitive). */
    fun findRoutines(q: String): List<Routine> = routines().filter { it.name.contains(q.trim(), ignoreCase = true) || it.instruction.contains(q.trim(), ignoreCase = true) }

    // ── Sync (AGENT_PLAN §7) ─────────────────────────────────────────────────

    /**
     * One pending edit to push: [kind] is "task"/"reminder"/"note"/"memory", [deleted]
     * marks a tombstone, and [data] is that entity's own fields as JSON — built fresh from
     * the live row when this is read, never stored twice. Conversations, documents,
     * projects, routines and activity are NOT synced: v1 covers exactly what AGENT_PLAN §7
     * names (tasks, reminders, notes, memory), and everything else stays local-first.
     */
    data class SyncRow(val kind: String, val id: String, val updatedAt: Long, val deleted: Boolean, val data: JSONObject)

    /** Local edits [SyncClient] hasn't pushed yet, oldest first. */
    fun pendingSync(limit: Int = 300): List<SyncRow> = query(
        "SELECT kind, ref_id, updated_at, deleted FROM sync_outbox ORDER BY updated_at LIMIT ?", limit,
    ) { r ->
        val kind = r.getString(1); val id = r.getString(2); val deleted = r.getInt(4) == 1
        SyncRow(kind, id, r.getLong(3), deleted, if (deleted) JSONObject() else payloadFor(kind, id) ?: JSONObject())
    }.filter { it.deleted || it.data.length() > 0 } // a row the outbox still remembers but was itself removed some other way

    /** After a successful push: these (kind, id) pairs need not be sent again. */
    fun clearSynced(entries: List<Pair<String, String>>) = tx {
        entries.forEach { (kind, id) -> exec("DELETE FROM sync_outbox WHERE kind=? AND ref_id=?", kind, id) }
    }

    /**
     * A row from ANOTHER device, already decided as the winner: write it locally and index
     * it, but never re-enqueue it — that would just push it straight back to the same
     * server that sent it. [markSynced] then clears any local edit it just overrode, so a
     * push this device already had in flight for the same id does not fight the pull.
     */
    fun applyRemoteRow(row: SyncRow) {
        when (row.kind) {
            "task" -> if (row.deleted) {
                exec("DELETE FROM tasks WHERE id=?", row.id); unindex(row.id)
            } else {
                val d = row.data
                exec(
                    """INSERT INTO tasks(id,title,notes,due_at,priority,status,project_id,source_conversation,created,updated,completed_at)
                       VALUES(?,?,?,?,?,?,NULL,NULL,?,?,?)
                       ON CONFLICT(id) DO UPDATE SET title=excluded.title, notes=excluded.notes, due_at=excluded.due_at,
                         priority=excluded.priority, status=excluded.status, updated=excluded.updated, completed_at=excluded.completed_at""",
                    row.id, d.optString("title"), d.optStringOrNull("notes"), d.optLongOrNull("dueAt"), d.optInt("priority", 0),
                    d.optString("status", "OPEN"), d.optLong("created", row.updatedAt), row.updatedAt, d.optLongOrNull("completedAt"),
                )
                index("task", row.id, d.optString("title"), d.optStringOrNull("notes").orEmpty())
            }
            "reminder" -> if (row.deleted) {
                exec("DELETE FROM reminders WHERE id=?", row.id)
            } else {
                val d = row.data
                // delivered/task_id are per-device (see deleteReminder's note); a brand new
                // row starts undelivered, an existing one keeps whatever this device already
                // recorded — an edit to the time or text is not, by itself, a fresh alarm.
                exec(
                    """INSERT INTO reminders(id,text,at,recurrence,delivered,task_id,created) VALUES(?,?,?,?,0,NULL,?)
                       ON CONFLICT(id) DO UPDATE SET text=excluded.text, at=excluded.at, recurrence=excluded.recurrence""",
                    row.id, d.optString("text"), d.optLong("at", row.updatedAt), d.optStringOrNull("recurrence"), d.optLong("created", row.updatedAt),
                )
            }
            "note" -> if (row.deleted) {
                exec("DELETE FROM notes WHERE id=?", row.id); unindex(row.id)
            } else {
                val d = row.data
                exec(
                    """INSERT INTO notes(id,title,body,project_id,source_conversation,created,updated) VALUES(?,?,?,NULL,NULL,?,?)
                       ON CONFLICT(id) DO UPDATE SET title=excluded.title, body=excluded.body, updated=excluded.updated""",
                    row.id, d.optString("title"), d.optString("body"), d.optLong("created", row.updatedAt), row.updatedAt,
                )
                index("note", row.id, d.optString("title"), d.optString("body"))
            }
            "memory" -> if (row.deleted) {
                exec("DELETE FROM memories WHERE id=?", row.id); unindex(row.id)
            } else {
                val d = row.data
                val kind = runCatching { MemoryKind.valueOf(d.optString("kind", "FACT")) }.getOrDefault(MemoryKind.FACT)
                exec(
                    """INSERT INTO memories(id,kind,text,source_conversation,created) VALUES(?,?,?,NULL,?)
                       ON CONFLICT(id) DO UPDATE SET kind=excluded.kind, text=excluded.text""",
                    row.id, kind.name, d.optString("text").take(MAX_MEMORY), d.optLong("created", row.updatedAt),
                )
                index("memory", row.id, kind.name.lowercase(), d.optString("text"))
            }
        }
        exec("DELETE FROM sync_outbox WHERE kind=? AND ref_id=?", row.kind, row.id)
    }

    /** How far pull has read: epoch ms of the last row this device has seen from the server. */
    fun syncCursor(): Long = query("SELECT value FROM meta WHERE key='sync_cursor'") { it.getString(1).toLong() }.firstOrNull() ?: 0L
    fun setSyncCursor(v: Long) = exec("INSERT OR REPLACE INTO meta(key,value) VALUES('sync_cursor', ?)", v.toString())

    /** This entity's fields as sent over the wire (never conversation/project links — those don't sync). */
    private fun payloadFor(kind: String, id: String): JSONObject? = when (kind) {
        "task" -> task(id)?.let {
            JSONObject().put("title", it.title).put("notes", it.notes).put("dueAt", it.dueAt).put("priority", it.priority)
                .put("status", it.status.name).put("completedAt", it.completedAt).put("created", it.created)
        }
        "reminder" -> reminder(id)?.let { JSONObject().put("text", it.text).put("at", it.at).put("recurrence", it.recurrence) }
        "note" -> note(id)?.let { JSONObject().put("title", it.title).put("body", it.body).put("created", it.updated) }
        "memory" -> memory(id)?.let { JSONObject().put("kind", it.kind.name).put("text", it.text).put("created", it.created) }
        else -> null
    }

    // ── Activity ─────────────────────────────────────────────────────────────

    /** Every action JARVIS takes is logged here (AGENT_PLAN §4) — the user can see what it did. */
    fun log(kind: String, summary: String, detail: String? = null): Activity {
        val a = Activity(newId(), clock(), kind, summary, detail)
        exec("INSERT INTO activity(id,at,kind,summary,detail) VALUES(?,?,?,?,?)", a.id, a.at, kind, summary, detail)
        return a
    }

    fun activity(limit: Int = 100): List<Activity> = query("SELECT * FROM activity ORDER BY at DESC LIMIT ?", limit) {
        Activity(it.getString("id"), it.getLong("at"), it.getString("kind"), it.getString("summary"), it.getString("detail"))
    }

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * One box over everything. Each word the user typed must appear (prefix match), in
     * any order; results are ranked by relevance. Messages resolve to their conversation.
     */
    fun search(text: String, limit: Int = 30): List<SearchHit> {
        val q = ftsQuery(text) ?: return emptyList()
        return query(
            "SELECT kind, ref_id, owner, title, snippet(search_fts, 4, '[', ']', '…', 12) AS snip " +
                "FROM search_fts WHERE search_fts MATCH ? ORDER BY rank LIMIT ?",
            q, limit,
        ) {
            val kind = it.getString("kind")
            if (kind == "message") {
                // A message hit is shown as the conversation it belongs to.
                val owner = it.getString("owner")
                SearchHit("conversation", owner, conversation(owner)?.title ?: "Conversation", it.getString("snip"))
            } else {
                SearchHit(kind, it.getString("ref_id"), it.getString("title"), it.getString("snip"))
            }
        }
            .distinctBy { it.kind to it.refId }
    }

    fun messageCount(): Int = query("SELECT count(*) FROM messages") { it.getInt(1) }.first()

    /** True when there is nothing in the brain yet (drives the one-time import). */
    fun isEmpty(): Boolean =
        query("SELECT (SELECT count(*) FROM conversations) + (SELECT count(*) FROM memories)") { it.getLong(1) }.first() == 0L

    override fun close() = db.close()

    // ── Internals ────────────────────────────────────────────────────────────

    private fun index(kind: String, refId: String, title: String, body: String, owner: String? = null) {
        exec("DELETE FROM search_fts WHERE ref_id=?", refId)
        exec("INSERT INTO search_fts(kind,ref_id,owner,title,body) VALUES(?,?,?,?,?)", kind, refId, owner, title, body)
    }

    private fun unindex(refId: String) = exec("DELETE FROM search_fts WHERE ref_id=?", refId)

    /**
     * Records that a sync-eligible row changed, for [pendingSync] to pick up later. One row
     * per (kind, id) — a second edit before the last sync just moves its clock forward,
     * rather than queuing every intermediate state.
     */
    private fun touch(kind: String, refId: String, deleted: Boolean = false) = exec(
        """INSERT INTO sync_outbox(kind,ref_id,updated_at,deleted) VALUES(?,?,?,?)
           ON CONFLICT(kind,ref_id) DO UPDATE SET updated_at=excluded.updated_at, deleted=excluded.deleted""",
        kind, refId, clock(), if (deleted) 1 else 0,
    )

    private fun conv(r: ResultSet) = Conversation(
        r.getString("id"), r.getString("title"), r.getString("project_id"), r.getInt("pinned") == 1,
        r.getInt("archived") == 1, r.getLong("created"), r.getLong("updated"),
    )

    private fun mem(r: ResultSet) = Memory(
        r.getString("id"), runCatching { MemoryKind.valueOf(r.getString("kind")) }.getOrDefault(MemoryKind.FACT),
        r.getString("text"), r.getString("source_conversation"), r.getLong("created"),
    )

    private fun task(r: ResultSet) = Task(
        r.getString("id"), r.getString("title"), r.getString("notes"), r.getLongOrNull("due_at"), r.getInt("priority"),
        if (r.getString("status") == "DONE") TaskStatus.DONE else TaskStatus.OPEN,
        r.getString("project_id"), r.getString("source_conversation"), r.getLong("created"), r.getLongOrNull("completed_at"),
    )

    private fun doc(r: ResultSet) = Document(
        r.getString("id"), r.getString("name"), r.getString("path"), r.getString("kind"), r.getString("unit"),
        r.getInt("pages"), r.getInt("chars"), r.getLongOrNull("modified"), r.getString("source_conversation"), r.getLong("created"),
    )

    /** Null for a row whose schedule can't be read (never written by this code, but never crash on it). */
    private fun routine(r: ResultSet): Routine? {
        val s = Schedule.decode(r.getString("schedule")) ?: return null
        return Routine(
            r.getString("id"), r.getString("name"), r.getString("instruction"), s, r.getLong("next_run"),
            r.getInt("enabled") == 1, r.getInt("speak") == 1, r.getLongOrNull("last_run"), r.getString("source_conversation"), r.getLong("created"),
        )
    }

    private fun rem(r: ResultSet) = Reminder(
        r.getString("id"), r.getString("text"), r.getLong("at"), r.getString("recurrence"), r.getInt("delivered") == 1, r.getString("task_id"),
    )

    private fun noteRow(r: ResultSet) = Note(
        r.getString("id"), r.getString("title"), r.getString("body"), r.getString("project_id"), r.getString("source_conversation"), r.getLong("updated"),
    )

    private fun ResultSet.getLongOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }

    /** [JSONObject] has no built-in nullable getters; `null`/absent both read back as null. */
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

    private fun exec(sql: String, vararg args: Any?) {
        db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    private fun <T> tx(block: () -> T): T {
        val auto = db.autoCommit
        db.autoCommit = false
        return try {
            block().also { db.commit() }
        } catch (e: Throwable) {
            db.rollback(); throw e
        } finally {
            db.autoCommit = auto
        }
    }

    private fun migrate() {
        db.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
        }
        val version = query("SELECT value FROM meta WHERE key='schema'") { it.getString(1).toInt() }.firstOrNull() ?: 0
        if (version < 1) tx {
            SCHEMA_V1.forEach { exec(it) }
            exec("INSERT OR REPLACE INTO meta(key,value) VALUES('schema','1')")
        }
        if (version < 2) tx {
            SCHEMA_V2.forEach { exec(it) }
            exec("INSERT OR REPLACE INTO meta(key,value) VALUES('schema','2')")
        }
        if (version < 3) tx {
            SCHEMA_V3.forEach { exec(it) }
            exec("INSERT OR REPLACE INTO meta(key,value) VALUES('schema','3')")
        }
        if (version < 4) tx {
            SCHEMA_V4.forEach { exec(it) }
            // Backfill: everything this laptop already had becomes a pending sync edit, so
            // turning sync on for the first time pushes the FULL existing local history —
            // not just what changes from this point on.
            exec("INSERT INTO sync_outbox(kind,ref_id,updated_at,deleted) SELECT 'task', id, updated, 0 FROM tasks")
            exec("INSERT INTO sync_outbox(kind,ref_id,updated_at,deleted) SELECT 'reminder', id, created, 0 FROM reminders")
            exec("INSERT INTO sync_outbox(kind,ref_id,updated_at,deleted) SELECT 'note', id, updated, 0 FROM notes")
            exec("INSERT INTO sync_outbox(kind,ref_id,updated_at,deleted) SELECT 'memory', id, created, 0 FROM memories")
            exec("INSERT OR REPLACE INTO meta(key,value) VALUES('schema','4')")
        }
    }

    companion object {
        const val MAX_MEMORY = 300

        fun open(file: File, clock: () -> Long = System::currentTimeMillis): Brain {
            file.parentFile?.mkdirs()
            return openUrl("jdbc:sqlite:${file.absolutePath}", clock)
        }

        /** A throwaway brain (tests). */
        fun inMemory(clock: () -> Long = System::currentTimeMillis): Brain = openUrl("jdbc:sqlite::memory:", clock)

        private fun openUrl(url: String, clock: () -> Long): Brain {
            val c = DriverManager.getConnection(url)
            c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            return Brain(c, clock).also { it.migrate() }
        }

        fun newId(): String = UUID.randomUUID().toString()

        /**
         * Turns what the user typed into a safe FTS5 query: each word becomes a quoted prefix
         * term (`"budg"*`), so punctuation or FTS operators in the input can never break the
         * query or change its meaning. Null when there are no words.
         */
        fun ftsQuery(text: String, all: Boolean = true): String? {
            val words = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filter { it.isNotBlank() }
                .let { w -> if (all) w else w.filter { it.length > 1 && it !in STOP_WORDS } }
                .distinct().take(if (all) 8 else 12).toList()
            if (words.isEmpty()) return null
            // all = every word must appear (the search box). Otherwise any word may, ranked by
            // relevance: a question about a document ("what is the late fee") rarely repeats
            // its wording exactly.
            return words.joinToString(if (all) " " else " OR ") { "\"$it\"*" }
        }

        private val STOP_WORDS = setOf(
            "the", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be",
            "what", "which", "who", "when", "where", "how", "does", "do", "did", "it", "this", "that", "with",
            "about", "from", "by", "as", "at", "me", "my", "tell", "say", "says", "document", "pdf", "file",
        )

        /**
         * v4 (Phase 7): one small "outbox" of local edits still waiting to be pushed —
         * appended to by [touch] whenever a task, reminder, note or memory changes, drained
         * by [SyncClient]. `(kind, ref_id)` is the primary key so several edits before the
         * next sync collapse into one row, not a growing log of every intermediate state.
         */
        private val SCHEMA_V4 = listOf(
            """CREATE TABLE sync_outbox(kind TEXT NOT NULL, ref_id TEXT NOT NULL, updated_at INTEGER NOT NULL,
               deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(kind, ref_id))""",
        )

        /** v3 (Phase 6): routines. */
        private val SCHEMA_V3 = listOf(
            """CREATE TABLE routines(id TEXT PRIMARY KEY, name TEXT NOT NULL, instruction TEXT NOT NULL, schedule TEXT NOT NULL,
               next_run INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, speak INTEGER NOT NULL DEFAULT 1,
               deleted INTEGER NOT NULL DEFAULT 0, last_run INTEGER, source_conversation TEXT, created INTEGER NOT NULL)""",
            "CREATE INDEX routines_due ON routines(deleted, enabled, next_run)",
        )

        /** v2 (Phase 5): documents, their pages, a passage index, and which chat each is attached to. */
        private val SCHEMA_V2 = listOf(
            """CREATE TABLE documents(id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT, kind TEXT NOT NULL, unit TEXT NOT NULL,
               pages INTEGER NOT NULL, chars INTEGER NOT NULL, modified INTEGER, source_conversation TEXT, created INTEGER NOT NULL)""",
            "CREATE INDEX documents_by_path ON documents(path, modified)",
            "CREATE TABLE doc_pages(doc_id TEXT NOT NULL, page INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(doc_id, page))",
            """CREATE VIRTUAL TABLE doc_fts USING fts5(doc_id UNINDEXED, page UNINDEXED, text,
               tokenize='unicode61 remove_diacritics 2')""",
            "CREATE TABLE conversation_docs(conversation_id TEXT NOT NULL, doc_id TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(conversation_id, doc_id))",
        )

        private val SCHEMA_V1 = listOf(
            """CREATE TABLE projects(id TEXT PRIMARY KEY, name TEXT NOT NULL, color TEXT,
               pinned INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, updated INTEGER NOT NULL)""",
            """CREATE TABLE conversations(id TEXT PRIMARY KEY, title TEXT NOT NULL, project_id TEXT,
               pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0,
               created INTEGER NOT NULL, updated INTEGER NOT NULL)""",
            """CREATE TABLE messages(id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, role TEXT NOT NULL,
               content TEXT NOT NULL, created INTEGER NOT NULL)""",
            "CREATE INDEX messages_by_conversation ON messages(conversation_id, created)",
            """CREATE TABLE memories(id TEXT PRIMARY KEY, kind TEXT NOT NULL, text TEXT NOT NULL,
               source_conversation TEXT, created INTEGER NOT NULL)""",
            """CREATE TABLE tasks(id TEXT PRIMARY KEY, title TEXT NOT NULL, notes TEXT, due_at INTEGER,
               priority INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, project_id TEXT, source_conversation TEXT,
               created INTEGER NOT NULL, updated INTEGER NOT NULL, completed_at INTEGER)""",
            "CREATE INDEX tasks_open_due ON tasks(status, due_at)",
            """CREATE TABLE reminders(id TEXT PRIMARY KEY, text TEXT NOT NULL, at INTEGER NOT NULL, recurrence TEXT,
               delivered INTEGER NOT NULL DEFAULT 0, task_id TEXT, created INTEGER NOT NULL)""",
            "CREATE INDEX reminders_pending ON reminders(delivered, at)",
            """CREATE TABLE notes(id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, project_id TEXT,
               source_conversation TEXT, created INTEGER NOT NULL, updated INTEGER NOT NULL)""",
            """CREATE TABLE activity(id TEXT PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL,
               summary TEXT NOT NULL, detail TEXT)""",
            // One search index over everything. kind/ref_id are stored, not searched.
            """CREATE VIRTUAL TABLE search_fts USING fts5(kind UNINDEXED, ref_id UNINDEXED, owner UNINDEXED, title, body,
               tokenize='unicode61 remove_diacritics 2')""",
        )
    }
}
