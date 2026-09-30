package com.jarvis.os.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainTest {

    private var now = 1_000_000L
    private fun brain() = Brain.inMemory(clock = { now })

    // ── Tasks ────────────────────────────────────────────────────────────────

    @Test
    fun `a task is added, found, completed and deleted`() {
        val b = brain()
        val t = b.addTask("Call the bank", dueAt = 2_000_000L, notes = "ask about the loan", priority = 2)
        assertEquals("Call the bank", b.task(t.id)?.title)
        assertEquals(Brain.TaskStatus.OPEN, b.task(t.id)?.status)

        now = 1_500_000L
        b.setTaskDone(t.id, true)
        assertEquals(Brain.TaskStatus.DONE, b.task(t.id)?.status)
        assertEquals(now, b.task(t.id)?.completedAt)
        assertTrue(b.doneTasks().any { it.id == t.id })
        assertTrue(b.openTasks().none { it.id == t.id })

        b.deleteTask(t.id)
        assertNull(b.task(t.id))
    }

    @Test
    fun `a blank task title is refused`() {
        val b = brain()
        var threw = false
        try {
            b.addTask("   ")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `open tasks sort overdue-and-dated first, then undated by priority`() {
        val b = brain()
        val soon = b.addTask("soon", dueAt = 100)
        val later = b.addTask("later", dueAt = 200)
        val undatedLow = b.addTask("undated low", priority = 0)
        val undatedHigh = b.addTask("undated high", priority = 3)
        val order = b.openTasks().map { it.id }
        assertEquals(listOf(soon.id, later.id, undatedHigh.id, undatedLow.id), order)
    }

    @Test
    fun `tasksDueBy returns only open tasks due before the cutoff`() {
        val b = brain()
        val overdue = b.addTask("overdue", dueAt = 50)
        b.addTask("future", dueAt = 500)
        val noDue = b.addTask("no due date")
        val done = b.addTask("done but overdue", dueAt = 10)
        b.setTaskDone(done.id, true)
        assertEquals(listOf(overdue.id), b.tasksDueBy(100).map { it.id })
        assertTrue(noDue.dueAt == null)
    }

    @Test
    fun `updateTask changes only what is passed, and can clear the due date`() {
        val b = brain()
        val t = b.addTask("Original", dueAt = 100, notes = "n1")
        b.updateTask(t.id, title = "Renamed")
        assertEquals("Renamed", b.task(t.id)?.title)
        assertEquals(100L, b.task(t.id)?.dueAt)
        b.updateTask(t.id, clearDue = true)
        assertNull(b.task(t.id)?.dueAt)
        assertEquals("n1", b.task(t.id)?.notes)
    }

    // ── Reminders ────────────────────────────────────────────────────────────

    @Test
    fun `a reminder becomes due once its time passes, and delivery is recorded`() {
        val b = brain()
        val r = b.addReminder("Take the medicine", at = 1_000_500L)
        assertTrue(b.dueReminders(now = 1_000_400L).isEmpty())
        assertEquals(listOf(r.id), b.dueReminders(now = 1_000_600L).map { it.id })
        b.markDelivered(r.id)
        assertTrue(b.dueReminders(now = 1_000_600L).isEmpty())
        assertTrue(b.reminder(r.id)!!.delivered)
    }

    @Test
    fun `upcomingReminders excludes delivered ones`() {
        val b = brain()
        val r1 = b.addReminder("one", at = 100)
        val r2 = b.addReminder("two", at = 200)
        b.markDelivered(r1.id)
        assertEquals(listOf(r2.id), b.upcomingReminders().map { it.id })
    }

    // ── Notes ────────────────────────────────────────────────────────────────

    @Test
    fun `a note is added, updated and deleted`() {
        val b = brain()
        val n = b.addNote("Shopping list", "milk, eggs")
        assertEquals("milk, eggs", b.note(n.id)?.body)
        b.updateNote(n.id, body = "milk, eggs, bread")
        assertEquals("milk, eggs, bread", b.note(n.id)?.body)
        b.deleteNote(n.id)
        assertNull(b.note(n.id))
    }

    @Test
    fun `notes sort most-recently-updated first`() {
        val b = brain()
        val first = b.addNote("first", "")
        now = 2_000_000L
        val second = b.addNote("second", "")
        assertEquals(listOf(second.id, first.id), b.notes().map { it.id })
    }

    // ── Memory ───────────────────────────────────────────────────────────────

    @Test
    fun `remembering the same fact twice only stores it once`() {
        val b = brain()
        val m1 = b.remember("The wifi password is sunflower")
        val m2 = b.remember("the WIFI password is sunflower")
        assertNotNull(m1)
        assertNull(m2)
        assertEquals(1, b.memories().size)
    }

    @Test
    fun `forget removes every memory mentioning the topic, and reports how many`() {
        val b = brain()
        b.remember("My dog is named Rex")
        b.remember("Rex needs his vaccine in June")
        b.remember("Unrelated fact")
        val removed = b.forget("Rex")
        assertEquals(2, removed)
        assertEquals(1, b.memories().size)
    }

    @Test
    fun `memories filter by kind`() {
        val b = brain()
        b.remember("Works at Acme", kind = Brain.MemoryKind.PROFILE)
        b.remember("Likes coffee", kind = Brain.MemoryKind.FACT)
        assertEquals(1, b.memories(Brain.MemoryKind.PROFILE).size)
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Test
    fun `search finds a task, a note and a memory by any matching word`() {
        val b = brain()
        b.addTask("Call the bank about the loan")
        b.addNote("Bank holiday", "Remember the bank is closed Monday")
        b.remember("My bank account number ends in 4471")
        val hits = b.search("bank")
        assertEquals(setOf("task", "note", "memory"), hits.map { it.kind }.toSet())
    }

    @Test
    fun `search requires every word to match, and finds nothing for a blank query`() {
        val b = brain()
        b.addTask("Call the bank")
        b.addNote("Unrelated", "nothing here")
        assertEquals(1, b.search("call bank").size)
        assertEquals(0, b.search("call unrelated").size)
        assertTrue(b.search("   ").isEmpty())
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    @Test
    fun `adding a task queues it for sync, and pushing clears it`() {
        val b = brain()
        val t = b.addTask("Sync me")
        val pending = b.pendingSync()
        assertEquals(1, pending.size)
        assertEquals("task", pending[0].kind)
        assertEquals(t.id, pending[0].id)
        assertEquals("Sync me", pending[0].data.getString("title"))

        b.clearSynced(listOf("task" to t.id))
        assertTrue(b.pendingSync().isEmpty())
    }

    @Test
    fun `editing a task twice before syncing collapses into one pending row`() {
        val b = brain()
        val t = b.addTask("Draft")
        b.updateTask(t.id, title = "Final")
        assertEquals(1, b.pendingSync().size)
        assertEquals("Final", b.pendingSync()[0].data.getString("title"))
    }

    @Test
    fun `deleting a task queues a tombstone, not a task payload`() {
        val b = brain()
        val t = b.addTask("Temporary")
        b.clearSynced(listOf("task" to t.id))
        b.deleteTask(t.id)
        val pending = b.pendingSync()
        assertEquals(1, pending.size)
        assertTrue(pending[0].deleted)
    }

    @Test
    fun `a remote task row is written locally and never re-queued for push`() {
        val b = brain()
        val data = JSONObject().put("title", "From laptop").put("notes", null as String?)
            .put("dueAt", null as Long?).put("priority", 1).put("status", "OPEN").put("completedAt", null as Long?)
        val remoteId = Brain.newId()
        b.applyRemoteRow(Brain.SyncRow("task", remoteId, 5_000_000L, deleted = false, data = data))
        assertEquals("From laptop", b.task(remoteId)?.title)
        assertTrue(b.pendingSync().none { it.id == remoteId })
    }

    @Test
    fun `a remote deletion removes the local row`() {
        val b = brain()
        val t = b.addTask("To be deleted remotely")
        b.clearSynced(listOf("task" to t.id))
        b.applyRemoteRow(Brain.SyncRow("task", t.id, 6_000_000L, deleted = true, data = JSONObject()))
        assertNull(b.task(t.id))
    }

    @Test
    fun `sync cursor round-trips, and starts at zero`() {
        val b = brain()
        assertEquals(0L, b.syncCursor())
        b.setSyncCursor(42L)
        assertEquals(42L, b.syncCursor())
    }

    @Test
    fun `a remote memory and note apply too`() {
        val b = brain()
        val memData = JSONObject().put("kind", "PROFILE").put("text", "Lives in Pune").put("created", 1L)
        b.applyRemoteRow(Brain.SyncRow("memory", Brain.newId(), 1L, deleted = false, data = memData))
        assertEquals(1, b.memories(Brain.MemoryKind.PROFILE).size)

        val noteData = JSONObject().put("title", "Remote note").put("body", "body text").put("created", 1L)
        val noteId = Brain.newId()
        b.applyRemoteRow(Brain.SyncRow("note", noteId, 1L, deleted = false, data = noteData))
        assertEquals("Remote note", b.note(noteId)?.title)
    }

    // ── Misc ─────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh brain is empty, adding anything makes it not`() {
        val b = brain()
        assertTrue(b.isEmpty())
        b.remember("something")
        assertTrue(!b.isEmpty())
    }

    @Test
    fun `reopening a file-backed brain keeps its data`() {
        val file = java.io.File.createTempFile("brain-test", ".db")
        file.delete()
        try {
            Brain.open(file, clock = { now }).use2 { it.addTask("Persisted") }
            val reopened = Brain.open(file, clock = { now })
            assertEquals(1, reopened.openTasks().size)
            reopened.close()
        } finally {
            file.delete()
        }
    }

    private fun <T> Brain.use2(block: (Brain) -> T): T = try { block(this) } finally { close() }
}
