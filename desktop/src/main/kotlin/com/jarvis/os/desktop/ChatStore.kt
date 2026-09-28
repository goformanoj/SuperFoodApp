package com.jarvis.os.desktop

import com.jarvis.os.data.ChatTurn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Conversations and remembered facts, persisted as one JSON file under [AppDirs] so
 * they survive a restart. Written whole after each change — it is small (turns and
 * conversations are capped) — via temp-then-rename.
 *
 * Format v2 holds many conversations. A v1 file (one flat `turns` list, from desktop
 * v0.1) is read as a single conversation, so nothing typed before the upgrade is lost.
 */
class ChatStore(private val file: File = AppDirs.file("chat.json")) {

    data class Conversation(
        val id: String,
        val title: String,
        val turns: List<ChatTurn>,
        val updatedMs: Long,
    )

    data class State(
        val conversations: List<Conversation> = emptyList(),
        val facts: List<String> = emptyList(),
    )

    fun load(): State = try {
        if (!file.exists()) State() else decode(file.readText())
    } catch (e: Exception) {
        State()
    }

    fun save(state: State) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(state))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    internal fun encode(state: State): String {
        val convs = JSONArray()
        state.conversations
            .filter { it.turns.isNotEmpty() }
            .sortedByDescending { it.updatedMs }
            .take(MAX_CONVERSATIONS)
            .forEach { c ->
                val turns = JSONArray()
                c.turns.takeLast(MAX_TURNS).forEach { turns.put(JSONObject().put("role", it.role).put("content", it.content)) }
                convs.put(JSONObject().put("id", c.id).put("title", c.title).put("updated", c.updatedMs).put("turns", turns))
            }
        val facts = JSONArray()
        state.facts.forEach { facts.put(it) }
        return JSONObject().put("v", 2).put("conversations", convs).put("facts", facts).toString()
    }

    internal fun decode(json: String): State {
        val o = JSONObject(json)
        val facts = o.optJSONArray("facts")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty()

        val conversations = if (o.has("conversations")) {
            val arr = o.getJSONArray("conversations")
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val turns = turnsOf(c.optJSONArray("turns"))
                if (turns.isEmpty()) return@mapNotNull null
                Conversation(
                    id = c.optString("id").ifBlank { "c$i" },
                    title = c.optString("title").ifBlank { DesktopTurn.titleFor(turns) },
                    turns = turns,
                    updatedMs = c.optLong("updated", 0L),
                )
            }
        } else {
            // v1: one flat conversation.
            val turns = turnsOf(o.optJSONArray("turns"))
            if (turns.isEmpty()) emptyList()
            else listOf(Conversation("c-v1", DesktopTurn.titleFor(turns), turns, file.lastModifiedOrZero()))
        }
        return State(conversations.sortedByDescending { it.updatedMs }, facts)
    }

    private fun turnsOf(arr: JSONArray?): List<ChatTurn> = arr?.let {
        (0 until it.length()).mapNotNull { i ->
            val t = it.optJSONObject(i) ?: return@mapNotNull null
            val role = t.optString("role")
            if (role != ChatTurn.USER && role != ChatTurn.ASSISTANT) null else ChatTurn(role, t.optString("content"))
        }
    }.orEmpty()

    private fun File.lastModifiedOrZero(): Long = if (exists()) lastModified() else 0L

    companion object {
        /** Kept on disk per conversation; far more than is ever sent as context. */
        const val MAX_TURNS = 400
        const val MAX_CONVERSATIONS = 200
    }
}
