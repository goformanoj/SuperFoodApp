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
    /**
     * [backend]/[model]: which platform and model actually answered (the Worker's router
     * fails over across several — see `backend/src/providers/`), present whenever the
     * Worker reports them. Tracked so a quality problem (e.g. degenerate/garbled text) can
     * be correlated to a specific backend instead of blamed on "the model" in general —
     * see [AgentLoop]'s `onReply` and `Eval.kt`'s degenerate-output check.
     */
    data class Reply(val text: String, val toolCalls: List<ToolCall>, val backend: String? = null, val model: String? = null)

    suspend fun step(messages: JSONArray, context: String, tools: JSONArray): Reply {
        val payload = JSONObject().put("messages", messages).put("tools", tools).apply {
            if (context.isNotBlank()) put("context", context)
        }.toString()
        return parse(postJson("/chat", payload))
    }

    /**
     * POSTs JSON to a metered Worker route (/chat, /search, /vision) with this laptop's
     * credentials; refreshes the token once on a 401; records the plan and allowance the
     * Worker reports. Returns the body, or throws [ProxyException] with a speakable sentence.
     */
    suspend fun postJson(path: String, payload: String, readTimeoutMs: Int = 45_000): String =
        request("POST", path, payload, readTimeoutMs)

    /** The GET counterpart (e.g. /sync/pull): same credentials, retry and error handling. */
    suspend fun getJson(path: String, readTimeoutMs: Int = 45_000): String =
        request("GET", path, null, readTimeoutMs)

    private suspend fun request(method: String, path: String, payload: String?, readTimeoutMs: Int): String = withContext(Dispatchers.IO) {
        var res = call(method, path, Identity.token(), payload, readTimeoutMs)
        if (res.first == 401) res = call(method, path, Identity.forceRefresh(), payload, readTimeoutMs)
        val (code, body) = res
        // JARVIS_AGENT_DEBUG=1: print the exchange (no credentials are in either side).
        if (System.getenv("JARVIS_AGENT_DEBUG") == "1") {
            System.err.println(">>> $method $path ${payload?.take(4000).orEmpty()}")
            System.err.println("<<< $code ${body.take(2000)}")
        }
        if (code !in 200..299) throw ProxyException(if (code == 0) "I couldn't reach my server — check the internet connection." else ProxyClient.errorMessage(code, body))
        ProxyClient.parsePlan(body)?.let { Identity.cachePlan(it) }
        ProxyClient.parseRemaining(body)?.let { UsageStats.record(ProxyClient.parsePlan(body), it) }
        body
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

    private fun call(method: String, path: String, token: String, payload: String?, readTimeoutMs: Int): Pair<Int, String> {
        val conn = URL(BuildConfig.WORKER_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.doOutput = payload != null
        conn.connectTimeout = 15000
        conn.readTimeout = readTimeoutMs
        if (payload != null) conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Proxy-Secret", BuildConfig.PROXY_SECRET)
        conn.setRequestProperty("Authorization", "Bearer $token")
        return try {
            payload?.let { p -> conn.outputStream.use { it.write(p.toByteArray(Charsets.UTF_8)) } }
            val code = conn.responseCode
            code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            0 to ""
        } finally {
            conn.disconnect()
        }
    }
}
