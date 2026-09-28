package com.jarvis.os.desktop.agent

import com.jarvis.os.BuildConfig
import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.ai.ProxyException
import com.jarvis.os.ai.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One step of the desktop agent against the Worker's /chat (Phase 4): the conversation
 * so far (including earlier tool calls and their results), the grounding context and the
 * tool schemas go up; either words or tool calls come back. Same credentials and error
 * wording as the phone's [ProxyClient], whose pure helpers it reuses.
 */
object AgentClient {

    data class ToolCall(val id: String, val name: String, val arguments: String)
    data class Reply(val text: String, val toolCalls: List<ToolCall>)

    suspend fun step(messages: JSONArray, context: String, tools: JSONArray): Reply = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("messages", messages).put("tools", tools).apply {
            if (context.isNotBlank()) put("context", context)
        }.toString()
        var res = post(Identity.token(), payload)
        if (res.first == 401) res = post(Identity.forceRefresh(), payload)
        val (code, body) = res
        if (code !in 200..299) throw ProxyException(if (code == 0) "I couldn't reach my server — check the internet connection." else ProxyClient.errorMessage(code, body))
        ProxyClient.parsePlan(body)?.let { Identity.cachePlan(it) }
        ProxyClient.parseRemaining(body)?.let { UsageStats.record(ProxyClient.parsePlan(body), it) }
        parse(body)
    }

    /** Pure; tested. */
    fun parse(body: String): Reply {
        val o = JSONObject(body)
        val calls = o.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                ToolCall(c.optString("id"), c.optString("name"), c.optString("arguments", "{}")).takeIf { it.name.isNotBlank() }
            }
        }.orEmpty()
        return Reply(o.optString("reply").trim(), calls)
    }

    /** The assistant message that records the model's tool calls (OpenAI shape), for the next step. */
    fun assistantToolMessage(text: String, calls: List<ToolCall>): JSONObject = JSONObject()
        .put("role", "assistant").put("content", text)
        .put("tool_calls", JSONArray().apply {
            calls.forEach { c ->
                put(JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", c.arguments)))
            }
        })

    fun toolResultMessage(callId: String, content: String): JSONObject =
        JSONObject().put("role", "tool").put("tool_call_id", callId).put("content", content)

    private fun post(token: String, payload: String): Pair<Int, String> {
        val conn = URL(BuildConfig.WORKER_URL.trimEnd('/') + "/chat").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 45000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Proxy-Secret", BuildConfig.PROXY_SECRET)
        conn.setRequestProperty("Authorization", "Bearer $token")
        return try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            0 to ""
        } finally {
            conn.disconnect()
        }
    }
}
