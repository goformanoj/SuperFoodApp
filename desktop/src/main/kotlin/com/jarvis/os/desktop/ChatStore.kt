package com.jarvis.os.desktop

import com.jarvis.os.data.ChatTurn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The conversation and remembered facts, persisted as one JSON file under
 * [AppDirs] so they survive a restart. Written whole after each turn — it is small
 * (history is capped, facts are capped), and a whole-file write is atomic enough
 * via the temp-then-rename below.
 */
class ChatStore(private val file: File = AppDirs.file("chat.json")) {

    data class State(val turns: List<ChatTurn> = emptyList(), val facts: List<String> = emptyList())

    /** Kept on disk; far more than is ever sent as context. */
    private val maxStoredTurns = 400

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
        val turns = JSONArray()
        state.turns.takeLast(maxStoredTurns).forEach {
            turns.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        val facts = JSONArray()
        state.facts.forEach { facts.put(it) }
        return JSONObject().put("turns", turns).put("facts", facts).toString()
    }

    internal fun decode(json: String): State {
        val o = JSONObject(json)
        val turns = o.optJSONArray("turns")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                val role = t.optString("role")
                if (role != ChatTurn.USER && role != ChatTurn.ASSISTANT) null
                else ChatTurn(role, t.optString("content"))
            }
        }.orEmpty()
        val facts = o.optJSONArray("facts")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty()
        return State(turns, facts)
    }
}
