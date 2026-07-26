package com.jarvis.os.ai

import com.jarvis.os.BuildConfig
import com.jarvis.os.data.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Thrown with a short, safe (no key) reason when a Gemini call fails. */
class GeminiException(message: String) : Exception(message)

/**
 * Minimal Gemini REST client (no SDK). Key from [BuildConfig.GEMINI_API_KEY].
 * Sends the recent conversation plus a grounding [context]. Falls through to the
 * next model only on 404; 429 reports a short rate-limit message.
 */
object GeminiClient {

    private val MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
    )

    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

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
            "the user confirms; keep your spoken reply short and natural. " +
            "This is a continuing conversation — use the prior turns for context and do " +
            "not act as if you have no memory. After completing a task, briefly confirm it " +
            "and ask if there's anything else. When the user indicates they are done " +
            "(e.g. 'no', 'nothing', 'that's all', 'thanks'), give a short sign-off and " +
            "append <<END>> on its own line (never read it aloud)."

    fun hasKey(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    suspend fun generate(messages: List<ChatTurn>, context: String): String =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.GEMINI_API_KEY
            if (key.isBlank()) throw GeminiException("No API key set")

            var lastReason = "No response"
            for (model in MODELS) {
                val outcome = requestModel(model, messages, context, key)
                if (outcome.text != null) return@withContext outcome.text
                lastReason = outcome.reason ?: "Unknown error"
                if (!outcome.retryable) throw GeminiException(lastReason)
            }
            throw GeminiException(lastReason)
        }

    private data class Outcome(val text: String?, val reason: String?, val retryable: Boolean)

    private fun requestModel(
        model: String,
        messages: List<ChatTurn>,
        context: String,
        key: String,
    ): Outcome {
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
            conn.outputStream.use {
                it.write(buildPayload(messages, context).toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val ok = code in 200..299
            val stream = if (ok) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (ok) {
                val text = parseFirstText(body)
                if (text.isBlank()) Outcome(null, "Empty reply from model", true)
                else Outcome(text, null, false)
            } else {
                val reason = if (code == 429) {
                    "Rate limit (429). Wait a minute and try once, or enable billing."
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

    private fun buildPayload(messages: List<ChatTurn>, context: String): String {
        val system = if (context.isBlank()) SYSTEM_PROMPT else "$SYSTEM_PROMPT\n\n$context"
        val contents = JSONArray()
        for (m in messages) {
            val role = if (m.role == ChatTurn.ASSISTANT) "model" else "user"
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", m.content))),
            )
        }
        return JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
            )
            put("contents", contents)
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
