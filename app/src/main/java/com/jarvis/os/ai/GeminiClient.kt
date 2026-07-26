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
 * Minimal Gemini REST client (no SDK). The API key comes from
 * [BuildConfig.GEMINI_API_KEY], injected at build time from a GitHub Actions
 * secret. On failure it throws [GeminiException] with a short reason (HTTP code
 * + API message, or a network error) so the UI can show what went wrong.
 */
object GeminiClient {

    private const val MODEL = "gemini-2.0-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

    private const val SYSTEM_PROMPT =
        "You are JARVIS, a concise and helpful voice assistant. " +
            "Answer in one or two short sentences suitable to be spoken aloud."

    fun hasKey(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    suspend fun generate(userText: String): String = withContext(Dispatchers.IO) {
        val key = BuildConfig.GEMINI_API_KEY
        if (key.isBlank()) throw GeminiException("No API key set")

        val conn = try {
            URL("$ENDPOINT/$MODEL:generateContent?key=$key").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw GeminiException("Connection error: ${e.javaClass.simpleName}")
        }
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Content-Type", "application/json")

        val payload = JSONObject().apply {
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
        }

        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val ok = code in 200..299
            val stream = if (ok) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw GeminiException("HTTP $code: ${extractError(body)}")
            val text = parseFirstText(body)
            if (text.isBlank()) throw GeminiException("Empty reply from model")
            text
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("Network error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

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
