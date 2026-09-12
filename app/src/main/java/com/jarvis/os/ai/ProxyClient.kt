package com.jarvis.os.ai

import com.jarvis.os.BuildConfig
import com.jarvis.os.data.ChatTurn
import com.jarvis.os.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Thrown with a short, safe (no token/secret) reason when a Worker call fails. */
class ProxyException(message: String) : Exception(message)

/**
 * Talks to the JARVIS Worker instead of Groq directly (Phase 4).
 *
 * The Worker holds the model key, meters tokens per user, and picks the model — so
 * this client is thinner than [GroqClient]: no key, no model list, no per-model
 * cooldown. It attaches the shared app secret ([BuildConfig.PROXY_SECRET], which
 * gates the app) and a signed Firebase ID token from [Identity] (which identifies
 * the user), posts the same conversation shape [GroqClient] sends, and returns the
 * Worker's `reply`.
 *
 * The system prompt now lives on the Worker: for the ordinary assistant turn we send
 * NO system and the Worker applies its own default (the server is the source of
 * truth). Only the small single-purpose prompts — the `<<PICK>>` chooser, the agent
 * loop, the ping — are sent as an override, exactly as before.
 *
 * `tier` is accepted for call-site compatibility but not sent: the Worker chooses
 * the model from the user's plan, not from a per-turn hint.
 *
 * The pure response/error parsing is [parseReply]/[errorMessage], tested off-device.
 */
object ProxyClient {

    private val WORKER_URL = BuildConfig.WORKER_URL.trimEnd('/')
    private val ENDPOINT = "$WORKER_URL/chat"

    /**
     * True when every piece the proxy path needs is present: the Worker URL, the
     * app secret, and a Firebase API key to mint a token with. When false, [Brain]
     * falls back to the direct providers.
     */
    fun isConfigured(): Boolean =
        WORKER_URL.isNotBlank() && BuildConfig.PROXY_SECRET.isNotBlank() && Identity.isConfigured()

    suspend fun generate(
        messages: List<ChatTurn>,
        context: String,
        systemOverride: String? = null,
        @Suppress("UNUSED_PARAMETER") tier: Tier = Tier.SMART,
    ): String = withContext(Dispatchers.IO) {
        var res = request(Identity.token(), messages, context, systemOverride)
        // A 401 is the one worth a single retry: the cached token may have expired
        // or the signing keys rotated. Mint a fresh one and try once more; anything
        // else (403 bad secret, 429 over cap, 5xx) is not helped by a new token.
        if (res.code == 401) {
            DebugLog.log(DebugLog.Stage.ERROR, "Worker rejected the token — refreshing once")
            res = request(Identity.forceRefresh(), messages, context, systemOverride)
        }
        res.text ?: throw ProxyException(res.reason ?: "Worker error")
    }

    /**
     * Matches a description to one on-screen option via the Worker. Same contract as
     * [GroqClient.chooseIndex]; reuses that one copy of the chooser prompt.
     */
    suspend fun chooseIndex(description: String, options: List<String>): Int? {
        if (options.isEmpty()) return null
        val listing = options.mapIndexed { i, option -> "${i + 1}. $option" }.joinToString("\n")
        val question = "On screen right now:\n$listing\n\nWhich one is \"$description\"?"
        val raw = generate(
            messages = listOf(ChatTurn(ChatTurn.USER, question)),
            context = "",
            systemOverride = GroqClient.CHOOSER_PROMPT,
            tier = Tier.FAST,
        )
        val number = Regex("""\d+""").find(raw)?.value?.toIntOrNull() ?: return null
        return number.takeIf { it in 1..options.size }
    }

    private data class Res(val code: Int, val text: String?, val reason: String?)

    private fun request(
        token: String,
        messages: List<ChatTurn>,
        context: String,
        systemOverride: String?,
    ): Res {
        val conn = try {
            URL(ENDPOINT).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Res(0, null, "Connection error: ${e.javaClass.simpleName}")
        }
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Proxy-Secret", BuildConfig.PROXY_SECRET)
        conn.setRequestProperty("Authorization", "Bearer $token")

        return try {
            conn.outputStream.use {
                it.write(buildPayload(messages, context, systemOverride).toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val ok = code in 200..299
            val stream = if (ok) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (ok) {
                // The reply carries the day's metering (plan + remaining). Cache it so
                // the drawer can show the plan and today's token usage with no extra call.
                val plan = parsePlan(body)
                if (plan != null) Identity.cachePlan(plan)
                parseRemaining(body)?.let { UsageStats.record(plan, it) }
                val reply = parseReply(body)
                if (reply.isBlank()) Res(code, null, "Empty reply from Worker") else Res(code, reply, null)
            } else {
                Res(code, null, errorMessage(code, body))
            }
        } catch (e: Exception) {
            Res(0, null, "Network error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    internal fun buildPayload(
        messages: List<ChatTurn>,
        context: String,
        systemOverride: String?,
    ): String {
        val msgArray = JSONArray()
        messages.forEach {
            msgArray.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        return JSONObject().apply {
            put("messages", msgArray)
            if (context.isNotBlank()) put("context", context)
            // Only send a system prompt when overriding; otherwise the Worker's own
            // default assistant prompt applies (the server is now the source of truth).
            if (systemOverride != null) put("system", systemOverride)
        }.toString()
    }

    internal fun parseReply(json: String): String {
        return try {
            JSONObject(json).optString("reply").trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** The plan the Worker metered this turn at, or null if absent/unparseable. */
    internal fun parsePlan(json: String): String? = try {
        JSONObject(json).optString("plan").ifBlank { null }
    } catch (e: Exception) {
        null
    }

    /** Tokens left in today's allowance, or null if the field is absent. */
    internal fun parseRemaining(json: String): Int? = try {
        val o = JSONObject(json)
        if (o.has("remaining")) o.optInt("remaining") else null
    } catch (e: Exception) {
        null
    }

    /** A short, user-safe reason from a non-2xx Worker response. Never leaks the token. */
    internal fun errorMessage(code: Int, body: String): String {
        val detail = try {
            val o = JSONObject(body)
            o.optString("message").ifBlank { o.optString("error") }
        } catch (e: Exception) {
            ""
        }
        return when (code) {
            401 -> "Sign-in failed — could not verify this device"
            403 -> "This app build is not authorised for the server"
            429 -> if (detail.isNotBlank()) detail else "Daily limit reached — try again tomorrow"
            else -> if (detail.isNotBlank()) "Server error: ${detail.take(160)}" else "Server error (HTTP $code)"
        }
    }
}
