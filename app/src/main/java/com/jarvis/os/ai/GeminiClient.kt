package com.jarvis.os.ai

import com.jarvis.os.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Thrown with a short, safe (no key) reason when a Gemini call fails. */
class GeminiException(message: String) : Exception(message)

/**
 * Minimal Gemini REST client (no SDK). Key comes from
 * [BuildConfig.GEMINI_API_KEY], injected at build time from a GitHub Actions
 * secret. Tries a list of models and falls through on quota/availability
 * errors (HTTP 429 / 404), since free-tier quotas are per-model. On failure it
 * throws [GeminiException] with a short reason so the UI can show what happened.
 */
object GeminiClient {

    // Current models that support generateContent (1.5 line is retired -> 404).
    // We only fall through to the next on 404 (model unavailable), NOT on 429,
    // so a single utterance makes a single request and stays under rate limits.
    private val MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
    )

    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

    private const val SYSTEM_PROMPT =
        "You are JARVIS, a concise and helpful voice assistant. " +
            "Answer in one or two short sentences suitable to be spoken aloud."

    fun hasKey(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    suspend fun generate(userText: String): String = withContext(Dispatchers.IO) {
        val key = BuildConfig.GEMINI_API_KEY
        if (key.isBlank()) throw GeminiException("No API key set")

        var lastReason = "No response"
        for (model in MODELS) {
            val outcome = requestModel(model, userText, key)
            if (outcome.text != null) return@withContext outcome.text
            lastReason = outcome.reason ?: "Unknown error"
            // Only 429 (quota) / 404 (model unavailable) are worth trying another
            // model for; key/format/network errors would fail the same way.
            if (!outcome.retryable) throw GeminiException(lastReason)
        }
        throw GeminiException(lastReason)
    }

    private data class Outcome(val text: String?, val reason: String?, val retryable: Boolean)

    private fun requestModel(model: String, userText: String, key: String): Outcome {
        val conn = try {
            URL("$ENDPOINT/$model:generateContent?key=$key").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Outcome(null, "Connection error: ${e.javaClass.simpleName}", false)
        }
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Content-Type", "application/json")

        return try {
            conn.outputStream.use { it.write(buildPayload(userText).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val ok = code in 200..299
            val stream = if (ok) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (ok) {
                val text = parseFirstText(body)
                if (text.isBlank()) Outcome(null, "Empty reply from model", true)
                else Outcome(text, null, false)
            } else {
                // Only 404 (model unavailable) is worth trying another model.
                // 429 is a rate/quota limit — trying more models just burns quota.
                val reason = if (code == 429) {
                    "Rate limit (429). Wait ~a minute and try once, or enable billing in Google AI Studio."
                } else {
                    "HTTP $code: ${extractError(body)}"
                }
                Outcome(null, reason, retryable = code == 404)
            }
        } catch (e: Exception) {
            Outcome(null, "Network error: ${e.message ?: e.javaClass.simpleName}", false)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildPayload(userText: String): String = JSONObject().apply {
        put(
            "system_instruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))),
        )
        put(
            "contents",
            JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", userText))),
            ),
        )
    }.toString()

    private fun extractError(body: String): String {
        return try {
            val msg = JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            if (msg.isNotBlank()) msg.take(160) else body.take(160)
        } catch (e: Exception) {
            body.take(160)
        }
    }

    private fun parseFirstText(json: String): String {
        return try {
            val candidates = JSONObject(json).optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text"))
            }
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }
}
