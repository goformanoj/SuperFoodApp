package com.jarvis.os.desktop.sync

import com.jarvis.os.desktop.brain.Brain
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A small stand-in for the Worker's /sync/push and /sync/pull — the SAME accept/overrule/
 * reject shape backend/src/sync.js implements, kept independently here so this test is
 * exercising SyncClient's handling of that wire protocol, not a mock of specific bytes.
 */
private class FakeServer {
    data class Row(val kind: String, val id: String, var updatedAt: Long, var deleted: Boolean, var data: JSONObject)

    val rows = LinkedHashMap<String, Row>()      // "kind|id" -> row, insertion order preserved
    var pushCalls = 0
    var pullCalls = 0
    val pushSizes = mutableListOf<Int>()
    val pullSinceSeen = mutableListOf<Long>()
    var pullPageSize = 500
    var now = 10_000L

    suspend fun push(@Suppress("UNUSED_PARAMETER") path: String, body: String): String {
        pushCalls++
        val incoming = JSONObject(body).getJSONArray("rows")
        pushSizes += incoming.length()
        val accepted = JSONArray()
        val serverWins = JSONArray()
        val rejected = JSONArray()
        for (i in 0 until incoming.length()) {
            val r = incoming.getJSONObject(i)
            val kind = r.getString("kind"); val id = r.getString("id"); val updatedAt = r.getLong("updatedAt")
            if (id == "reject-me") { rejected.put(JSONObject().put("id", id).put("error", "data_too_large")); continue }
            val key = "$kind|$id"
            val existing = rows[key]
            if (existing == null || updatedAt > existing.updatedAt) {
                rows[key] = Row(kind, id, updatedAt, r.optBoolean("deleted", false), r.optJSONObject("data") ?: JSONObject())
                accepted.put(id)
            } else {
                serverWins.put(rowJson(existing))
            }
        }
        return JSONObject().put("accepted", accepted).put("serverWins", serverWins).put("rejected", rejected).toString()
    }

    suspend fun pull(path: String): String {
        pullCalls++
        val since = path.substringAfter("since=").substringBefore('&').toLong()
        pullSinceSeen += since
        val changed = rows.values.filter { it.updatedAt > since }.sortedBy { it.updatedAt }
        val page = changed.take(pullPageSize)
        return JSONObject().put("rows", JSONArray(page.map { rowJson(it) })).put("hasMore", changed.size > page.size).put("serverTime", now).toString()
    }

    fun seedServerRow(kind: String, id: String, updatedAt: Long, data: JSONObject, deleted: Boolean = false) {
        rows["$kind|$id"] = Row(kind, id, updatedAt, deleted, data)
    }

    private fun rowJson(r: Row) = JSONObject().put("kind", r.kind).put("id", r.id).put("updatedAt", r.updatedAt).put("deleted", r.deleted)
        .apply { if (!r.deleted) put("data", r.data) }
}

class SyncClientTest {

    private var now = 1_000L
    private val brain = Brain.inMemory { now }
    private val server = FakeServer()
    private val client = SyncClient(brain, postJson = server::push, getJson = server::pull)
    @After fun close() = brain.close()

    // ── push ──

    @Test
    fun everyPendingEditGoesUpAndTheOutboxEmpties() = runBlocking {
        brain.addTask("Call the bank")
        brain.addReminder("Pay rent", 5_000L)
        assertTrue(client.pushOnce())
        assertTrue(brain.pendingSync().isEmpty())
        assertEquals(1, server.pushCalls)
        assertEquals(setOf("task", "reminder"), server.rows.values.map { it.kind }.toSet())
    }

    @Test
    fun nothingPendingIsAOneCallNoOp() = runBlocking {
        assertFalse(client.pushOnce())
        assertEquals(0, server.pushCalls)
    }

    @Test
    fun aLargeBacklogGoesUpInBatchesNeverOneGiantRequest() = runBlocking {
        repeat(250) { brain.addTask("t$it") }
        assertTrue(client.pushOnce())
        assertTrue(brain.pendingSync().isEmpty())
        assertEquals(2, server.pushCalls)                      // 200 + 50, not 1 request of 250
        assertEquals(listOf(SyncClient.BATCH_SIZE, 50), server.pushSizes)
    }

    @Test
    fun aServerOverruleReplacesTheLocalCopyAndStopsRetryingIt() = runBlocking {
        // The server already has a NEWER version of a task this laptop is about to push.
        server.seedServerRow("task", "t1", 9_000L, JSONObject().put("title", "From the phone").put("status", "OPEN"))
        val local = brain.addTask("Local title")
        // Force the outbox row to look like id "t1" with an OLDER clock than the server's.
        brain.applyRemoteRow(Brain.SyncRow("task", "t1", 1L, false, JSONObject().put("title", "placeholder")))
        // (applyRemoteRow clears the outbox; re-touch it as a genuinely older local edit.)
        now = 500L
        brain.updateTask("t1", title = "Local edit, but clock is behind the server's")
        assertTrue(client.pushOnce())
        assertEquals("From the phone", brain.task("t1")!!.title)   // the server's copy won
        assertTrue(brain.pendingSync().none { it.id == "t1" })      // and it's not queued to fight the pull again
        assertEquals("Local title", brain.task(local.id)!!.title)    // the unrelated task is untouched
    }

    @Test
    fun aRowTheServerCanNeverAcceptIsDroppedNotRetriedForever() = runBlocking {
        now = 100L
        val t = brain.addTask("normal")
        // Simulate an outbox entry the server always rejects (see FakeServer.push: id "reject-me").
        brain.applyRemoteRow(Brain.SyncRow("task", "reject-me", 50L, false, JSONObject().put("title", "x")))
        now = 200L
        brain.updateTask("reject-me", title = "still bad")
        assertTrue(client.pushOnce())
        assertTrue(brain.pendingSync().none { it.id == "reject-me" })
        assertTrue(brain.activity().any { it.summary.contains("Dropped") })
        assertTrue(brain.pendingSync().none { it.id == t.id })   // the good row still went through in the same call
    }

    // ── pull ──

    @Test
    fun aFreshPullBringsInEveryRowSinceZero() = runBlocking {
        server.seedServerRow("task", "p1", 100L, JSONObject().put("title", "From another device"))
        server.seedServerRow("reminder", "p2", 200L, JSONObject().put("text", "Phone reminder").put("at", 9_000L))
        assertTrue(client.pullOnce())
        assertEquals("From another device", brain.task("p1")!!.title)
        assertEquals("Phone reminder", brain.reminder("p2")!!.text)
        assertEquals(200L, brain.syncCursor())
        assertTrue(brain.pendingSync().isEmpty())        // pulled rows are not re-queued to push
    }

    @Test
    fun aSecondPullOnlyAsksForWhatsNewSinceLastTime() = runBlocking {
        server.seedServerRow("task", "p1", 100L, JSONObject().put("title", "first"))
        client.pullOnce()
        server.seedServerRow("task", "p2", 300L, JSONObject().put("title", "second"))
        client.pullOnce()
        assertEquals(listOf(0L, 100L), server.pullSinceSeen)
        assertEquals(300L, brain.syncCursor())
    }

    @Test
    fun anEmptyPullStillAdvancesTheCursorToTheServersClock() = runBlocking {
        server.now = 5_000L
        assertFalse(client.pullOnce())
        assertEquals(5_000L, brain.syncCursor())
    }

    @Test
    fun aLargePullPagesUntilHasMoreIsFalse() = runBlocking {
        server.pullPageSize = 2
        repeat(5) { i -> server.seedServerRow("task", "p$i", (i + 1) * 10L, JSONObject().put("title", "t$i")) }
        assertTrue(client.pullOnce())
        assertEquals(3, server.pullCalls)                 // 2 + 2 + 1
        assertEquals(listOf(0L, 20L, 40L), server.pullSinceSeen)
        assertEquals(5, (0..4).count { brain.task("p$it") != null })
        assertEquals(50L, brain.syncCursor())
    }

    @Test
    fun aTombstonePulledFromAnotherDeviceRemovesTheLocalRow() = runBlocking {
        val id = brain.addTask("Old task").id
        brain.clearSynced(listOf("task" to id)) // pretend it's already synced
        server.seedServerRow("task", id, 999L, JSONObject(), deleted = true)
        client.pullOnce()
        assertNull(brain.task(id))
    }

    // ── the round trip together ──

    @Test
    fun syncOnceIsTrueWhenEitherHalfChangedAnythingAndFalseWhenNothingDid() = runBlocking {
        assertFalse(client.syncOnce())
        brain.addTask("mine")
        assertTrue(client.syncOnce())      // the push half changed something
        assertFalse(client.syncOnce())     // now both halves are caught up
        // The cursor has already advanced to server.now (10_000) from the empty pulls above,
        // so a NEW row from another device — like a real one, always AFTER whatever was
        // already seen — needs a later clock, not an earlier one.
        server.now = 20_000L
        server.seedServerRow("note", "n1", 15_000L, JSONObject().put("title", "x").put("body", "y"))
        assertTrue(client.syncOnce())      // the pull half changed something
    }

    // ── pure wire mapping ──

    @Test
    fun rowToJsonOmitsDataForATombstoneAndRoundTripsThroughJsonToRow() {
        // org.json's JSONObject compares by reference, not content, so these compare the
        // plain fields and the JSON text separately rather than the whole data class.
        val live = Brain.SyncRow("task", "t1", 100L, false, JSONObject().put("title", "x"))
        val encoded = SyncClient.rowToJson(live)
        assertEquals("x", encoded.getJSONObject("data").getString("title"))
        val back = SyncClient.jsonToRow(encoded)
        assertEquals(live.kind, back.kind); assertEquals(live.id, back.id)
        assertEquals(live.updatedAt, back.updatedAt); assertEquals(live.deleted, back.deleted)
        assertEquals(live.data.toString(), back.data.toString())

        val tomb = Brain.SyncRow("task", "t1", 200L, true, JSONObject())
        val encodedTomb = SyncClient.rowToJson(tomb)
        assertFalse(encodedTomb.has("data"))
        val backTomb = SyncClient.jsonToRow(encodedTomb)
        assertEquals(tomb.deleted, backTomb.deleted)
        assertEquals(0, backTomb.data.length())
    }
}
