package com.jarvis.os.desktop.knowledge

import com.jarvis.os.desktop.agent.AgentClient
import org.json.JSONObject
import java.util.Base64

/**
 * The Worker's knowledge routes (AGENT_PLAN §5): the live web (/search) and screenshot
 * questions (/vision). Both are metered against the same daily allowance as chat, with
 * the same credentials ([AgentClient.postJson]).
 */
object KnowledgeClient {

    data class Source(val title: String, val url: String)
    data class WebAnswer(val answer: String, val sources: List<Source>)

    suspend fun webSearch(query: String, context: String = ""): WebAnswer {
        val payload = JSONObject().put("query", query.take(400)).apply { if (context.isNotBlank()) put("context", context) }
        // A search reads several pages before answering: give it longer than a chat turn.
        return parseWeb(AgentClient.postJson("/search", payload.toString(), readTimeoutMs = 90_000))
    }

    suspend fun askAboutImage(jpeg: ByteArray, question: String, context: String = ""): String {
        val payload = JSONObject()
            .put("image", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg))
            .put("question", question.take(1000))
            .apply { if (context.isNotBlank()) put("context", context) }
        return JSONObject(AgentClient.postJson("/vision", payload.toString(), readTimeoutMs = 90_000)).optString("answer").trim()
    }

    /** Pure; tested. */
    fun parseWeb(body: String): WebAnswer {
        val o = JSONObject(body)
        val arr = o.optJSONArray("sources")
        val sources = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val s = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val url = s.optString("url")
            if (!url.startsWith("http")) null else Source(s.optString("title").ifBlank { url }, url)
        }
        return WebAnswer(o.optString("answer").trim(), sources)
    }
}
