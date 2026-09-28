package com.jarvis.os.desktop.brain

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
    }

    fun memories(kind: MemoryKind? = null): List<Memory> = if (kind == null) {
        query("SELECT * FROM memories ORDER BY created") { mem(it) }
    } else {
        query("SELECT * FROM memories WHERE kind=? ORDER BY created", kind.name) { mem(it) }
    }

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
        return t
    }

    fun task(id: String): Task? = query("SELECT * FROM tasks WHERE id=?", id) { task(it) }.firstOrNull()

    fun setTaskDone(id: String, done: Boolean) {
        val now = clock()
        exec("UPDATE tasks SET status=?, completed_at=?, updated=? WHERE id=?", if (done) "DONE" else "OPEN", if (done) now else null, now, id)
    }

    fun updateTask(id: String, title: String? = null, dueAt: Long? = null, clearDue: Boolean = false, notes: String? = null) {
        val t = task(id) ?: return
        val newTitle = title?.trim()?.ifEmpty { null } ?: t.title
        val newDue = if (clearDue) null else dueAt ?: t.dueAt
        val newNotes = notes ?: t.notes
        exec("UPDATE tasks SET title=?, due_at=?, notes=?, updated=? WHERE id=?", newTitle, newDue, newNotes, clock(), id)
        index("task", id, newTitle, newNotes.orEmpty())
    }

    fun deleteTask(id: String) {
        exec("DELETE FROM tasks WHERE id=?", id)
        unindex(id)
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
        return r
    }

    /** Undelivered reminders whose time has come — what the notifier fires. */
    fun dueReminders(now: Long = clock()): List<Reminder> =
        query("SELECT * FROM reminders WHERE delivered=0 AND at<=? ORDER BY at", now) { rem(it) }

    fun upcomingReminders(): List<Reminder> =
        query("SELECT * FROM reminders WHERE delivered=0 ORDER BY at") { rem(it) }

    fun markDelivered(id: String) = exec("UPDATE reminders SET delivered=1 WHERE id=?", id)
    fun deleteReminder(id: String) = exec("DELETE FROM reminders WHERE id=?", id)

    // ── Notes ────────────────────────────────────────────────────────────────

    fun addNote(title: String, body: String, projectId: String? = null, sourceConversation: String? = null): Note {
        val now = clock()
        val n = Note(newId(), title.trim().ifEmpty { "Untitled note" }, body, projectId, sourceConversation, now)
        exec("INSERT INTO notes(id,title,body,project_id,source_conversation,created,updated) VALUES(?,?,?,?,?,?,?)", n.id, n.title, body, projectId, sourceConversation, now, now)
        index("note", n.id, n.title, body)
        return n
    }

    fun notes(): List<Note> = query("SELECT * FROM notes ORDER BY updated DESC") {
        Note(it.getString("id"), it.getString("title"), it.getString("body"), it.getString("project_id"), it.getString("source_conversation"), it.getLong("updated"))
    }

    fun deleteNote(id: String) {
        exec("DELETE FROM notes WHERE id=?", id)
        unindex(id)
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

    private fun rem(r: ResultSet) = Reminder(
        r.getString("id"), r.getString("text"), r.getLong("at"), r.getString("recurrence"), r.getInt("delivered") == 1, r.getString("task_id"),
    )

    private fun ResultSet.getLongOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }

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
