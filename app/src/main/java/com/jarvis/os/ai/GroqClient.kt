package com.jarvis.os.ai

import com.jarvis.os.BuildConfig
import com.jarvis.os.data.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Thrown with a short, safe (no key) reason when a Groq call fails. */
class GroqException(message: String) : Exception(message)

/**
 * Groq chat-completions client (OpenAI-compatible API). Free tier, no billing
 * card required. Key comes from [BuildConfig.GROQ_API_KEY]. Sends the recent
 * conversation plus a grounding [context]. Falls through to the next model only
 * on 404 (model decommissioned); 429 reports a short rate-limit message.
 */
object GroqClient {

    private val MODELS = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "gemma2-9b-it",
    )

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    private const val SYSTEM_PROMPT =
        "You are JARVIS, a concise and helpful voice assistant. " +
            "Answer in one or two short sentences suitable to be spoken aloud. " +
            "You can manage the user's calendar. After the user clearly confirms, output " +
            "command lines (each on its own line, 24-hour time, never read them aloud): " +
            "add -> <<CAL|ADD|Title|YYYY-MM-DD|HH:MM|60>> (last field = duration minutes); " +
            "delete/cancel -> <<CAL|DEL|Title|YYYY-MM-DD|HH:MM>>. " +
            "To RESCHEDULE or move an event, output a DEL line for its CURRENT date/time " +
            "AND an ADD line for the new one. To cancel or remove an event, output ONLY a " +
            "DEL line — never add a replacement. Use the provided upcoming events to find " +
            "the exact current title/date/time. Ask one short follow-up only if you cannot " +
            "identify the event or are missing the new time. Never output a command before " +
            "the user confirms; keep your spoken reply short and natural."

    fun hasKey(): Boolean = BuildConfig.GROQ_API_KEY.isNotBlank()

    suspend fun generate(messages: List<ChatTurn>, context: String): String =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.GROQ_API_KEY
            if (key.isBlank()) throw GroqException("No Groq API key set")

            var lastReason = "No response"
            for (model in MODELS) {
                val outcome = requestModel(model, messages, context, key)
                if (outcome.text != null) return@withContext outcome.text
                lastReason = outcome.reason ?: "Unknown error"
                if (!outcome.retryable) throw GroqException(lastReason)
            }
            throw GroqException(lastReason)
        }

    private data class Outcome(val text: String?, val reason: String?, val retryable: Boolean)

    private fun requestModel(
        model: String,
        messages: List<ChatTurn>,
        context: String,
        key: String,
    ): Outcome {
        val conn = try {
            URL(ENDPOINT).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Outcome(null, "Connection error: ${e.javaClass.simpleName}", false)
        }
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $key")

        return try {
            conn.outputStream.use {
                it.write(buildPayload(model, messages, context).toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val ok = code in 200..299
            val stream = if (ok) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (ok) {
                val text = parseContent(body)
                if (text.isBlank()) Outcome(null, "Empty reply from model", true)
                else Outcome(text, null, false)
            } else {
                val reason = if (code == 429) {
                    "Rate limit (429). Wait a moment and try once."
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

    private fun buildPayload(model: String, messages: List<ChatTurn>, context: String): String {
        val system = if (context.isBlank()) SYSTEM_PROMPT else "$SYSTEM_PROMPT\n\n$context"
        val msgArray = JSONArray()
        msgArray.put(JSONObject().put("role", "system").put("content", system))
        messages.forEach {
            msgArray.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0.7)
            put("max_tokens", 300)
            put("messages", msgArray)
        }.toString()
    }

    private fun extractError(body: String): String {
        return try {
            val msg = JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            if (msg.isNotBlank()) msg.take(160) else body.take(160)
        } catch (e: Exception) {
            body.take(160)
        }
    }

    private fun parseContent(json: String): String {
        return try {
            val choices = JSONObject(json).optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .trim()
        } catch (e: Exception) {
            ""
        }
    }
}
