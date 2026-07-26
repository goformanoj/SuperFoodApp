package com.jarvis.os.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the conversation as JSON in SharedPreferences so it survives app
 * restarts. Lightweight on purpose — no database/codegen dependency.
 */
class ConversationStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("jarvis_chat", Context.MODE_PRIVATE)

    fun load(): MutableList<ChatTurn> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ChatTurn>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(ChatTurn(o.optString("role"), o.optString("content")))
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(turns: List<ChatTurn>) {
        val arr = JSONArray()
        turns.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "history"
    }
}
