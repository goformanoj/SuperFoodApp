package com.jarvis.os.desktop.brain

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 (AGENT_PLAN §7): the local half of syncing tasks, reminders, notes and memory. */
class BrainSyncTest {

    private var now = 1_000L
    private val brain = Brain.inMemory { now }
    @After fun close() = brain.close()

    private fun pending() = brain.pendingSync()
    private fun outboxKeys() = pending().map { it.kind to it.id }.toSet()

    // ── every mutation enqueues exactly one outbox row ──

    @Test
    fun addingATaskQueuesItForSync() {
        val t = brain.addTask("Call the bank")
        val row = pending().single()
        assertEquals("task" to t.id, row.kind to row.id)
        assertFalse(row.deleted)
        assertEquals("Call the bank", row.data.getString("title"))
        assertEquals(now, row.updatedAt)
    }

    @Test
    fun severalEditsBeforeSyncCollapseIntoOneRow() {
        val t = brain.addTask("Call the bank")
        now = 2_000L
        brain.updateTask(t.id, notes = "before 5pm")
        now = 3_000L
        brain.setTaskDone(t.id, true)
        val rows = pending().filter { it.id == t.id }
        assertEquals(1, rows.size)
        assertEquals(3_000L, rows.single().updatedAt)
        assertEquals("DONE", rows.single().data.getString("status"))
    }

    @Test
    fun deletingQueuesATombstoneWithNoData() {
        val t = brain.addTask("x")
        brain.clearSynced(listOf("task" to t.id))    // pretend it already synced once
        brain.deleteTask(t.id)
        val row = pending().single()
        assertTrue(row.deleted)
        assertEquals(0, row.data.length())
    }

    @Test
    fun reminderNoteAndMemoryAllQueueToo() {
        val r = brain.addReminder("Call the bank", 5_000L)
        val n = brain.addNote("Ideas", "Body text")
        val m = brain.remember("Likes tea")
        assertEquals(setOf("reminder" to r.id, "note" to n.id, "memory" to m!!.id), outboxKeys())
        val rr = pending().single { it.kind == "reminder" }.data
        assertEquals("Call the bank", rr.getString("text"))
        assertEquals(5_000L, rr.getLong("at"))
    }

    @Test
    fun forgettingSeveralMemoriesQueuesATombstoneForEach() {
        brain.remember("Loves cats")
        brain.remember("Loves dogs")
        brain.remember("Likes tea")
        brain.forget("loves")
        val tombstones = pending().filter { it.kind == "memory" && it.deleted }
        assertEquals(2, tombstones.size)
    }

    @Test
    fun clearSyncedRemovesExactlyThoseEntries() {
        val a = brain.addTask("a"); val b = brain.addTask("b")
        brain.clearSynced(listOf("task" to a.id))
        assertEquals(setOf("task" to b.id), outboxKeys())
    }

    // ── applying rows pulled from another device ──

    @Test
    fun aTaskFromAnotherDeviceAppearsLocallyAndIsNotReEnqueued() {
        val row = Brain.SyncRow("task", "remote-1", 500L, false, JSONObject().put("title", "Book flights").put("priority", 2).put("status", "OPEN"))
        brain.applyRemoteRow(row)
        val t = brain.task("remote-1")!!
        assertEquals("Book flights", t.title)
        assertEquals(2, t.priority)
        assertTrue(pending().none { it.id == "remote-1" })       // never pushed straight back
        assertTrue(brain.openTasks().any { it.id == "remote-1" })
    }

    @Test
    fun aDoneTaskFromAnotherDeviceArrivesDone() {
        val row = Brain.SyncRow("task", "r2", 500L, false, JSONObject().put("title", "x").put("status", "DONE").put("completedAt", 400L))
        brain.applyRemoteRow(row)
        assertEquals(Brain.TaskStatus.DONE, brain.task("r2")!!.status)
        assertEquals(400L, brain.task("r2")!!.completedAt)
    }

    @Test
    fun applyingATombstoneRemovesTheLocalCopy() {
        brain.applyRemoteRow(Brain.SyncRow("task", "r3", 100L, false, JSONObject().put("title", "gone soon")))
        brain.applyRemoteRow(Brain.SyncRow("task", "r3", 200L, true, JSONObject()))
        assertNull(brain.task("r3"))
    }

    @Test
    fun aRemoteReminderStartsUndeliveredAndKeepsItsOwnDeliveryOnUpdate() {
        brain.applyRemoteRow(Brain.SyncRow("reminder", "rem1", 100L, false, JSONObject().put("text", "call mom").put("at", 5_000L)))
        assertFalse(brain.upcomingReminders().single().delivered)
        brain.markDelivered("rem1")        // THIS device delivered it
        // The phone edits the time; this device's own delivered flag is not reset by the pull.
        brain.applyRemoteRow(Brain.SyncRow("reminder", "rem1", 200L, false, JSONObject().put("text", "call mom").put("at", 6_000L)))
        assertEquals(6_000L, brain.reminder("rem1")!!.at)
        assertTrue(brain.reminder("rem1")!!.delivered)
    }

    @Test
    fun aRemoteNoteAndMemoryApplyAndReindex() {
        brain.applyRemoteRow(Brain.SyncRow("note", "n1", 100L, false, JSONObject().put("title", "Trip").put("body", "Pack early")))
        assertEquals("Pack early", brain.note("n1")!!.body)
        assertTrue(brain.search("pack early").any { it.refId == "n1" })

        brain.applyRemoteRow(Brain.SyncRow("memory", "m1", 100L, false, JSONObject().put("kind", "PERSON").put("text", "Sister is Asha")))
        assertEquals(Brain.MemoryKind.PERSON, brain.memory("m1")!!.kind)
        assertTrue(brain.search("asha").any { it.refId == "m1" })
    }

    @Test
    fun applyingAnUnknownMemoryKindFallsBackToFactRatherThanCrashing() {
        brain.applyRemoteRow(Brain.SyncRow("memory", "m2", 100L, false, JSONObject().put("kind", "NONSENSE").put("text", "x")))
        assertEquals(Brain.MemoryKind.FACT, brain.memory("m2")!!.kind)
    }

    // ── the pull cursor ──

    @Test
    fun theCursorStartsAtZeroAndPersists() {
        assertEquals(0L, brain.syncCursor())
        brain.setSyncCursor(12_345L)
        assertEquals(12_345L, brain.syncCursor())
        brain.setSyncCursor(20_000L)        // overwrites, doesn't insert a second row
        assertEquals(20_000L, brain.syncCursor())
    }

    // ── upgrading an existing (pre-Phase-7) database backfills the outbox ──

    @Test
    fun anOlderLocalDatabaseBackfillsItsExistingDataIntoTheOutboxOnUpgrade() {
        val file = kotlin.io.path.createTempFile(suffix = ".db").toFile().also { it.delete() }
        try {
            Brain.open(file).use { b ->
                b.addTask("Old task")
                b.addReminder("Old reminder", 9_000L)
                b.addNote("Old note", "body")
                b.remember("Old fact")
            }
            // Simulate a laptop that last ran the Phase 6 build: schema 3, no outbox table,
            // but real tasks/reminders/notes/memories already sitting in the database.
            java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().use { st ->
                    st.execute("DROP TABLE sync_outbox")
                    st.execute("UPDATE meta SET value='3' WHERE key='schema'")
                }
            }
            Brain.open(file).use { b ->
                val kinds = b.pendingSync().map { it.kind }.toSet()
                assertEquals(setOf("task", "reminder", "note", "memory"), kinds)
                assertEquals(1, b.openTasks().size)   // nothing lost, nothing duplicated
            }
        } finally {
            file.delete()
        }
    }
}
