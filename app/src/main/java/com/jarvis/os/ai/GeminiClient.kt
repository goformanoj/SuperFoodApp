package com.jarvis.os.ai

import com.jarvis.os.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Gemini REST client (no SDK dependency). The API key comes from
 * [BuildConfig.GEMINI_API_KEY], injected at build time from a GitHub Actions
 * secret. Returns an empty string on any failure; the caller treats blank as
 * an error.
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
        if (key.isBlank()) return@withContext ""

        val url = URL("$ENDPOINT/$MODEL:generateContent?key=$key")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
        }

        val payload = JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)),
                ),
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", userText)),
                    ),
                ),
            )
        }

        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return@withContext ""
            parseFirstText(body)
        } catch (e: Exception) {
            ""
        } finally {
            conn.disconnect()
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
