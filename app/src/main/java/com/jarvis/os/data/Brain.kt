package com.jarvis.os.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * JARVIS's brain on the phone (AGENT_PLAN §7, phone side) — a real local store for tasks,
 * reminders, notes and typed memory, replacing the old "memory is a list of strings in
 * SharedPreferences" design. Mirrors the shape of the laptop's `desktop/.../brain/Brain.kt`
 * closely enough that the two can sync through the same Worker rows (Phase 7 next step);
 * it does NOT yet port conversations/projects/documents/routines — those stay as they are
 * on the phone for now, since Phase 7 sync only covers tasks/reminders/notes/memory anyway
 * (see the laptop Brain's own comment on `SyncRow`).
 *
 * IDs are random UUID strings (not autoincrement), so rows made on the phone and the laptop
 * can sync without colliding. Times are epoch milliseconds.
 *
 * **No FTS5 here, unlike the laptop.** Spiked first (see git history): Robolectric's SQLite
 * shadow has no FTS5 module ("no such module: fts5"), and real Android devices only
 * guarantee it consistently from Android 11 — this app's minSdk is 26. Plain `LIKE` search
 * is used instead: for a personal list of tasks/notes/memories (tens to low hundreds of
 * rows, not thousands), the performance difference from FTS5 is not a real problem, and it
 * behaves identically in tests and on every device instead of varying by OS version.
 *
 * Takes a plain [SQLiteDatabase], not a [Context] — same reason as the laptop taking a raw
 * JDBC `Connection`: it can be opened in-memory for fast, real unit tests (see BrainTest)
 * without any Android instrumentation, and the one place that needs a [Context] is the
 * companion factory that resolves a database file path.
 */
class Brain private constructor(private val db: SQLiteDatabase, private val clock: () -> Long) {

    // ── Model ────────────────────────────────────────────────────────────────

    enum class MemoryKind { PROFILE, PERSON, PLACE, PROJECT, INSTRUCTION, FACT }
    data class Memory(val id: String, val kind: MemoryKind, val text: String, val created: Long)

    enum class TaskStatus { OPEN, DONE }
    data class Task(
        val id: String, val title: String, val notes: String?, val dueAt: Long?, val priority: Int,
        val status: TaskStatus, val created: Long, val updated: Long, val completedAt: Long?,
    )

    data class Reminder(val id: String, val text: String, val at: Long, val recurrence: String?, val delivered: Boolean)
    data class Note(val id: String, val title: String, val body: String, val updated: Long)
    data class SearchHit(val kind: String, val refId: String, val title: String, val snippet: String)

    // ── Tasks ────────────────────────────────────────────────────────────────

    fun addTask(title: String, dueAt: Long? = null, notes: String? = null, priority: Int = 0): Task {
        val clean = title.trim().ifEmpty { throw IllegalArgumentException("task title is blank") }
        val now = clock()
        val t = Task(newId(), clean, notes?.trim()?.ifEmpty { null }, dueAt, priority.coerceIn(0, 3), TaskStatus.OPEN, now, now, null)
        exec(
            "INSERT INTO tasks(id,title,notes,due_at,priority,status,created,updated,completed_at) VALUES(?,?,?,?,?,?,?,?,?)",
            t.id, t.title, t.notes, t.dueAt, t.priority.toLong(), t.status.name, t.created, t.updated, null,
        )
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
        touch("task", id)
    }

    fun deleteTask(id: String) {
        exec("DELETE FROM tasks WHERE id=?", id)
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

    fun addReminder(text: String, at: Long, recurrence: String? = null): Reminder {
        val clean = text.trim().ifEmpty { throw IllegalArgumentException("reminder text is blank") }
        val r = Reminder(newId(), clean, at, recurrence, false)
        exec("INSERT INTO reminders(id,text,at,recurrence,delivered,created) VALUES(?,?,?,?,0,?)", r.id, r.text, at, recurrence, clock())
        touch("reminder", r.id)
        return r
    }

    fun reminder(id: String): Reminder? = query("SELECT * FROM reminders WHERE id=?", id) { rem(it) }.firstOrNull()

    /** Undelivered reminders whose time has come — what the notifier fires. */
    fun dueReminders(now: Long = clock()): List<Reminder> =
        query("SELECT * FROM reminders WHERE delivered=0 AND at<=? ORDER BY at", now) { rem(it) }

    fun upcomingReminders(): List<Reminder> = query("SELECT * FROM reminders WHERE delivered=0 ORDER BY at") { rem(it) }

    // Delivery is deliberately NOT synced — see the laptop Brain's own note: it's each
    // device's own record of whether IT notified the user, so the phone and the laptop
    // each still notify once, instead of one device's alert silencing the other's.
    fun markDelivered(id: String) = exec("UPDATE reminders SET delivered=1 WHERE id=?", id)

    fun deleteReminder(id: String) {
        exec("DELETE FROM reminders WHERE id=?", id)
        touch("reminder", id, deleted = true)
    }

    // ── Notes ────────────────────────────────────────────────────────────────

    fun addNote(title: String, body: String): Note {
        val now = clock()
        val n = Note(newId(), title.trim().ifEmpty { "Untitled note" }, body, now)
        exec("INSERT INTO notes(id,title,body,created,updated) VALUES(?,?,?,?,?)", n.id, n.title, body, now, now)
        touch("note", n.id)
        return n
    }

    fun notes(): List<Note> = query("SELECT * FROM notes ORDER BY updated DESC") { noteRow(it) }
    fun note(id: String): Note? = query("SELECT * FROM notes WHERE id=?", id) { noteRow(it) }.firstOrNull()

    fun updateNote(id: String, title: String? = null, body: String? = null) {
        val n = note(id) ?: return
        val newTitle = title?.trim()?.ifEmpty { null } ?: n.title
        val newBody = body ?: n.body
        exec("UPDATE notes SET title=?, body=?, updated=? WHERE id=?", newTitle, newBody, clock(), id)
        touch("note", id)
    }

    fun deleteNote(id: String) {
        exec("DELETE FROM notes WHERE id=?", id)
        touch("note", id, deleted = true)
    }

    // ── Memory ───────────────────────────────────────────────────────────────

    /** Adds a memory; returns null if the same text is already remembered (case-insensitive). */
    fun remember(text: String, kind: MemoryKind = MemoryKind.FACT): Memory? {
        val clean = text.trim().take(MAX_MEMORY)
        if (clean.isEmpty()) return null
        val exists = query("SELECT 1 FROM memories WHERE lower(text)=lower(?)", clean) { true }.isNotEmpty()
        if (exists) return null
        val m = Memory(newId(), kind, clean, clock())
        exec("INSERT INTO memories(id,kind,text,created) VALUES(?,?,?,?)", m.id, kind.name, clean, m.created)
        touch("memory", m.id)
        return m
    }

    /** Same FORGET semantics as the phone's old marker-based one: every memory mentioning [about] goes. Returns how many. */
    fun forget(about: String): Int {
        val needle = about.trim()
        if (needle.isEmpty()) return 0
        val ids = query("SELECT id FROM memories WHERE instr(lower(text), lower(?)) > 0", needle) { it.getString(0) }
        ids.forEach { deleteMemory(it) }
        return ids.size
    }

    fun deleteMemory(id: String) {
        exec("DELETE FROM memories WHERE id=?", id)
        touch("memory", id, deleted = true)
    }

    fun memories(kind: MemoryKind? = null): List<Memory> = if (kind == null) {
        query("SELECT * FROM memories ORDER BY created") { mem(it) }
    } else {
        query("SELECT * FROM memories WHERE kind=? ORDER BY created", kind.name) { mem(it) }
    }

    fun memory(id: String): Memory? = query("SELECT * FROM memories WHERE id=?", id) { mem(it) }.firstOrNull()

    // ── Search (plain LIKE — see the class doc comment for why not FTS5) ──────

    /** Every word must appear (case-insensitive substring) in the title/body/text, across tasks, notes and memory. */
    fun search(text: String, limit: Int = 30): List<SearchHit> {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filter { it.isNotBlank() }.distinct().take(8).toList()
        if (words.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        openTasks().plus(doneTasks(1000)).forEach { t ->
            if (matchesAll(words, t.title, t.notes.orEmpty())) hits += SearchHit("task", t.id, t.title, (t.notes ?: t.title).take(140))
        }
        notes().forEach { n ->
            if (matchesAll(words, n.title, n.body)) hits += SearchHit("note", n.id, n.title, n.body.take(140))
        }
        memories().forEach { m ->
            if (matchesAll(words, m.text)) hits += SearchHit("memory", m.id, m.text.take(60), m.text.take(140))
        }
        return hits.take(limit)
    }

    private fun matchesAll(words: List<String>, vararg haystack: String): Boolean {
        val joined = haystack.joinToString(" ").lowercase()
        return words.all { joined.contains(it) }
    }

    // ── Sync (AGENT_PLAN §7) — same shape as the laptop's, ready for SyncClient ──

    /** One pending edit to push: [kind] is "task"/"reminder"/"note"/"memory". */
    data class SyncRow(val kind: String, val id: String, val updatedAt: Long, val deleted: Boolean, val data: JSONObject)

    /** Local edits not yet pushed, oldest first. */
    fun pendingSync(limit: Int = 300): List<SyncRow> = query(
        "SELECT kind, ref_id, updated_at, deleted FROM sync_outbox ORDER BY updated_at LIMIT ?", limit,
    ) { c ->
        val kind = c.getString(0); val id = c.getString(1); val deleted = c.getInt(3) == 1
        SyncRow(kind, id, c.getLong(2), deleted, if (deleted) JSONObject() else payloadFor(kind, id) ?: JSONObject())
    }.filter { it.deleted || it.data.length() > 0 }

    /** After a successful push: these (kind, id) pairs need not be sent again. */
    fun clearSynced(entries: List<Pair<String, String>>) = tx {
        entries.forEach { (kind, id) -> exec("DELETE FROM sync_outbox WHERE kind=? AND ref_id=?", kind, id) }
    }

    /** A row from another device, already decided as the winner: write it locally, never re-enqueue it. */
    fun applyRemoteRow(row: SyncRow) {
        when (row.kind) {
            // No SQLite upsert syntax here (`ON CONFLICT ... DO UPDATE`): Robolectric's test
            // SQLite predates it ("near ON: syntax error"), and real device SQLite versions
            // vary too. Each case updates first and inserts only if nothing was updated —
            // portable to any SQLite, and the same pattern lets a field like a reminder's
            // `delivered` flag or a note's `created` timestamp be preserved on conflict,
            // which a blanket INSERT OR REPLACE would have overwritten.
            "task" -> if (row.deleted) {
                exec("DELETE FROM tasks WHERE id=?", row.id)
            } else {
                val d = row.data
                // Every column is being set either way, so a full replace is exactly right here.
                exec(
                    "INSERT OR REPLACE INTO tasks(id,title,notes,due_at,priority,status,created,updated,completed_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    row.id, d.optString("title"), d.optStringOrNull("notes"), d.optLongOrNull("dueAt"), d.optInt("priority", 0).toLong(),
                    d.optString("status", "OPEN"), d.optLong("created", row.updatedAt), row.updatedAt, d.optLongOrNull("completedAt"),
                )
            }
            "reminder" -> if (row.deleted) {
                exec("DELETE FROM reminders WHERE id=?", row.id)
            } else {
                val d = row.data
                exec(
                    "UPDATE reminders SET text=?, at=?, recurrence=? WHERE id=?",
                    d.optString("text"), d.optLong("at", row.updatedAt), d.optStringOrNull("recurrence"), row.id,
                )
                // delivered is per-device (see markDelivered's note): a brand-new row starts
                // undelivered; an existing one keeps whatever this device already recorded,
                // which is exactly why this falls back to INSERT only when nothing existed.
                if (rowsChanged() == 0) exec(
                    "INSERT INTO reminders(id,text,at,recurrence,delivered,created) VALUES(?,?,?,?,0,?)",
                    row.id, d.optString("text"), d.optLong("at", row.updatedAt), d.optStringOrNull("recurrence"), d.optLong("created", row.updatedAt),
                )
            }
            "note" -> if (row.deleted) {
                exec("DELETE FROM notes WHERE id=?", row.id)
            } else {
                val d = row.data
                exec("UPDATE notes SET title=?, body=?, updated=? WHERE id=?", d.optString("title"), d.optString("body"), row.updatedAt, row.id)
                if (rowsChanged() == 0) exec(
                    "INSERT INTO notes(id,title,body,created,updated) VALUES(?,?,?,?,?)",
                    row.id, d.optString("title"), d.optString("body"), d.optLong("created", row.updatedAt), row.updatedAt,
                )
            }
            "memory" -> if (row.deleted) {
                exec("DELETE FROM memories WHERE id=?", row.id)
            } else {
                val d = row.data
                val kind = runCatching { MemoryKind.valueOf(d.optString("kind", "FACT")) }.getOrDefault(MemoryKind.FACT)
                exec("UPDATE memories SET kind=?, text=? WHERE id=?", kind.name, d.optString("text").take(MAX_MEMORY), row.id)
                if (rowsChanged() == 0) exec(
                    "INSERT INTO memories(id,kind,text,created) VALUES(?,?,?,?)",
                    row.id, kind.name, d.optString("text").take(MAX_MEMORY), d.optLong("created", row.updatedAt),
                )
            }
        }
        exec("DELETE FROM sync_outbox WHERE kind=? AND ref_id=?", row.kind, row.id)
    }

    /** How far pull has read: epoch ms of the last row this device has seen from the server. */
    fun syncCursor(): Long = query("SELECT value FROM meta WHERE key='sync_cursor'") { it.getString(0).toLong() }.firstOrNull() ?: 0L
    fun setSyncCursor(v: Long) = exec("INSERT OR REPLACE INTO meta(key,value) VALUES('sync_cursor', ?)", v.toString())

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

    /** True when there is nothing in the brain yet (drives a one-time import from the old SharedPreferences store). */
    fun isEmpty(): Boolean =
        query("SELECT (SELECT count(*) FROM tasks)+(SELECT count(*) FROM reminders)+(SELECT count(*) FROM notes)+(SELECT count(*) FROM memories)") {
            it.getLong(0)
        }.first() == 0L

    fun close() = db.close()

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Records that a sync-eligible row changed, for [pendingSync] to pick up later. One row
     * per (kind, id) — a second edit before the next sync just moves its clock forward.
     */
    private fun touch(kind: String, refId: String, deleted: Boolean = false) = exec(
        // A full-row replace, not an upsert: every column is set here either way, and
        // `ON CONFLICT ... DO UPDATE` isn't portable to every SQLite build (see applyRemoteRow).
        "INSERT OR REPLACE INTO sync_outbox(kind,ref_id,updated_at,deleted) VALUES(?,?,?,?)",
        kind, refId, clock(), if (deleted) 1L else 0L,
    )

    /** Rows affected by the most recent statement on this connection — SQLite's built-in `changes()`. */
    private fun rowsChanged(): Int = query("SELECT changes()") { it.getInt(0) }.first()

    private fun task(c: Cursor) = Task(
        c.getStringOr("id"), c.getStringOr("title"), c.getStringOrNull("notes"), c.getLongOrNull("due_at"), c.getIntOr("priority"),
        if (c.getStringOr("status") == "DONE") TaskStatus.DONE else TaskStatus.OPEN,
        c.getLongOr("created"), c.getLongOr("updated"), c.getLongOrNull("completed_at"),
    )

    private fun rem(c: Cursor) = Reminder(
        c.getStringOr("id"), c.getStringOr("text"), c.getLongOr("at"), c.getStringOrNull("recurrence"), c.getIntOr("delivered") == 1,
    )

    private fun noteRow(c: Cursor) = Note(c.getStringOr("id"), c.getStringOr("title"), c.getStringOr("body"), c.getLongOr("updated"))

    private fun mem(c: Cursor) = Memory(
        c.getStringOr("id"), runCatching { MemoryKind.valueOf(c.getStringOr("kind")) }.getOrDefault(MemoryKind.FACT),
        c.getStringOr("text"), c.getLongOr("created"),
    )

    private fun Cursor.getStringOr(col: String): String = getString(getColumnIndexOrThrow(col))
    private fun Cursor.getStringOrNull(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.getLongOr(col: String): Long = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.getLongOrNull(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.getIntOr(col: String): Int = getInt(getColumnIndexOrThrow(col))

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

    /** Android's `execSQL` bind args must be Long/Double/byte[]/String/null — everything else is coerced here. */
    private fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map(::bindable).toTypedArray())
    }

    private fun bindable(v: Any?): Any? = when (v) {
        null, is Long, is Double, is String, is ByteArray -> v
        is Int -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        else -> v.toString()
    }

    /** `rawQuery`'s args are always bound as text; SQLite's column-affinity rules still compare them correctly against INTEGER columns. */
    private fun <T> query(sql: String, vararg args: Any?, map: (Cursor) -> T): List<T> {
        val strArgs = args.map { it?.toString() }.toTypedArray()
        return db.rawQuery(sql, strArgs).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }
    }

    private fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        return try {
            block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun migrate() {
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
        val version = query("SELECT value FROM meta WHERE key='schema'") { it.getString(0).toInt() }.firstOrNull() ?: 0
        if (version < 1) tx {
            SCHEMA_V1.forEach { db.execSQL(it) }
            db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('schema','1')")
        }
    }

    companion object {
        const val MAX_MEMORY = 300

        /** The phone's real brain file, in the app's private storage. */
        fun open(context: Context, clock: () -> Long = System::currentTimeMillis): Brain =
            open(context.getDatabasePath("brain.db"), clock)

        fun open(file: File, clock: () -> Long = System::currentTimeMillis): Brain {
            file.parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            db.execSQL("PRAGMA foreign_keys=ON")
            return Brain(db, clock).also { it.migrate() }
        }

        /** A throwaway brain (tests). */
        fun inMemory(clock: () -> Long = System::currentTimeMillis): Brain {
            val db = SQLiteDatabase.create(null)
            return Brain(db, clock).also { it.migrate() }
        }

        fun newId(): String = UUID.randomUUID().toString()

        private val SCHEMA_V1 = listOf(
            """CREATE TABLE tasks(id TEXT PRIMARY KEY, title TEXT NOT NULL, notes TEXT, due_at INTEGER,
               priority INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, created INTEGER NOT NULL,
               updated INTEGER NOT NULL, completed_at INTEGER)""",
            "CREATE INDEX tasks_open_due ON tasks(status, due_at)",
            """CREATE TABLE reminders(id TEXT PRIMARY KEY, text TEXT NOT NULL, at INTEGER NOT NULL, recurrence TEXT,
               delivered INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)""",
            "CREATE INDEX reminders_pending ON reminders(delivered, at)",
            "CREATE TABLE notes(id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)",
            "CREATE TABLE memories(id TEXT PRIMARY KEY, kind TEXT NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL)",
            // Phase 7: local edits waiting to be pushed. (kind, ref_id) is the primary key so
            // several edits before the next sync collapse into one row.
            """CREATE TABLE sync_outbox(kind TEXT NOT NULL, ref_id TEXT NOT NULL, updated_at INTEGER NOT NULL,
               deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(kind, ref_id))""",
        )
    }
}
