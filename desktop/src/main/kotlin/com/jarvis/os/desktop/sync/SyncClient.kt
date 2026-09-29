package com.jarvis.os.desktop.sync

import com.jarvis.os.desktop.agent.AgentClient
import com.jarvis.os.desktop.brain.Brain
import org.json.JSONArray
import org.json.JSONObject

/**
 * The network half of Phase 7 sync (AGENT_PLAN §7): pushes this laptop's pending edits to
 * the Worker's /sync/push, then pulls whatever other devices pushed from /sync/pull. The
 * decisions (who wins, what a row looks like) are made by the Worker and [Brain]; this
 * class only moves rows across the wire and tells [Brain] what happened.
 *
 * Deliberately thin and stateless — every call re-reads what it needs from [Brain] — so it
 * is called from a plain periodic loop rather than needing its own lifecycle.
 *
 * The two network calls are constructor parameters, not a hardcoded [AgentClient] call —
 * the same lambda-injection [AgentLoop] and [ToolBox] already use — so the batching,
 * cursor and accepted/serverWins/rejected handling below are tested against a fake server
 * (SyncClientTest), not only verified live.
 */
class SyncClient(
    private val brain: Brain,
    private val postJson: suspend (path: String, body: String) -> String = { p, b -> AgentClient.postJson(p, b) },
    private val getJson: suspend (path: String) -> String = { AgentClient.getJson(it) },
) {

    /** A push then a pull. Returns true if anything actually changed locally (worth a UI refresh). */
    suspend fun syncOnce(): Boolean {
        val pushed = pushOnce()
        val pulled = pullOnce()
        return pushed || pulled
    }

    /** Sends every pending local edit, in batches; applies corrections the server made. */
    suspend fun pushOnce(): Boolean {
        var changed = false
        while (true) {
            val rows = brain.pendingSync(BATCH_SIZE)
            if (rows.isEmpty()) return changed
            val payload = JSONObject().put("rows", JSONArray(rows.map(::rowToJson)))
            val res = JSONObject(postJson("/sync/push", payload.toString()))

            val accepted = res.optJSONArray("accepted").toStringSet()
            val toClear = rows.filter { it.id in accepted }.map { it.kind to it.id }
            if (toClear.isNotEmpty()) { brain.clearSynced(toClear); changed = true }

            // The server had a newer copy of these — take it, exactly as a pull would.
            res.optJSONArray("serverWins")?.let { arr ->
                for (i in 0 until arr.length()) { brain.applyRemoteRow(jsonToRow(arr.getJSONObject(i))); changed = true }
            }

            // A row the server could never accept as-is (e.g. too large): stop offering it,
            // rather than retrying the same failure forever and blocking everything behind it.
            res.optJSONArray("rejected")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val rej = arr.getJSONObject(i)
                    val row = rows.firstOrNull { it.id == rej.optString("id") } ?: continue
                    brain.clearSynced(listOf(row.kind to row.id))
                    brain.log("sync", "Dropped an unsynced ${row.kind} edit the server refused (${rej.optString("error")}); it stays as it is on this laptop")
                }
            }
            if (rows.size < BATCH_SIZE) return changed // fewer than a full batch: nothing more is waiting
        }
    }

    /** Pulls everything changed on other devices since this laptop last asked, a page at a time. */
    suspend fun pullOnce(): Boolean {
        var changed = false
        var since = brain.syncCursor()
        while (true) {
            val res = JSONObject(getJson("/sync/pull?since=$since"))
            val rows = res.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until rows.length()) {
                brain.applyRemoteRow(jsonToRow(rows.getJSONObject(i)))
                changed = true
            }
            since = if (rows.length() > 0) rows.getJSONObject(rows.length() - 1).getLong("updatedAt") else res.optLong("serverTime", since)
            brain.setSyncCursor(since)
            if (!res.optBoolean("hasMore", false)) return changed
        }
    }

    companion object {
        /** Comfortably under the Worker's MAX_ROWS_PER_PUSH, so this client is never the one hitting that cap. */
        const val BATCH_SIZE = 200

        /** Pure; tested. */
        fun rowToJson(r: Brain.SyncRow): JSONObject = JSONObject()
            .put("kind", r.kind).put("id", r.id).put("updatedAt", r.updatedAt).put("deleted", r.deleted)
            .apply { if (!r.deleted) put("data", r.data) }

        fun jsonToRow(o: JSONObject): Brain.SyncRow =
            Brain.SyncRow(o.getString("kind"), o.getString("id"), o.getLong("updatedAt"), o.optBoolean("deleted", false), o.optJSONObject("data") ?: JSONObject())

        private fun JSONArray?.toStringSet(): Set<String> =
            if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it, null) }.toSet()
    }
}
