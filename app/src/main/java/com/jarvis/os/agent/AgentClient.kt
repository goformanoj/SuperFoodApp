package com.jarvis.os.agent

import com.jarvis.os.BuildConfig
import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.ai.ProxyException
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One step of the phone agent against the Worker's /chat (AGENT_PLAN §7, phone side): the
 * conversation so far (including earlier tool calls and their results) and the tool
 * schemas go up; either words or tool calls come back. Ported from the laptop's own
 * `desktop/.../agent/AgentClient.kt`, reusing the phone's existing [ProxyClient] for its
 * pure response parsing and error wording so the two never silently drift apart.
 *
 * No `context` parameter (unlike the laptop's): the phone doesn't yet have a desktop-style
 * "what's on screen" grounding string to send; add one here if/when it does.
 */
object AgentClient {

    data class ToolCall(val id: String, val name: String, val arguments: String)
    /** [backend]/[model]: which platform actually answered — see the laptop's own AgentClient.kt for why this is tracked. */
    data class Reply(val text: String, val toolCalls: List<ToolCall>, val backend: String? = null, val model: String? = null)

    suspend fun step(messages: JSONArray, tools: JSONArray): Reply {
        val payload = JSONObject().put("messages", messages).put("tools", tools).toString()
        return parse(post(payload))
    }

    private suspend fun post(payload: String): String {
        var res = call(Identity.token(), payload)
        if (res.first == 401) res = call(Identity.forceRefresh(), payload)
        val (code, body) = res
        if (code !in 200..299) throw ProxyException(if (code == 0) "I couldn't reach my server — check the internet connection." else ProxyClient.errorMessage(code, body))
        ProxyClient.parsePlan(body)?.let { Identity.cachePlan(it) }
        return body
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
        return Reply(o.optString("reply").trim(), calls, o.optString("backend").ifBlank { null }, o.optString("model").ifBlank { null })
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

    private fun call(token: String, payload: String): Pair<Int, String> {
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
