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

    private val SYSTEM_PROMPT = """
        You are JARVIS, a concise and honest voice assistant. Your replies are spoken aloud, so keep them to a single short sentence when possible — natural, with no lists or long explanations.

        This is a continuing conversation: use the earlier turns for context and never act as if you have no memory. If a request is missing a detail you need, ask one short, relevant clarifying question rather than guessing.

        You CAN: chat and answer questions; read the user's device calendar and add, delete, or reschedule events; and — when the user clearly asks — open an app on the phone and tap or open a control, scrolling to find it if it isn't immediately visible.

        You CANNOT yet: type text into fields, set alarms or reminders, send texts or emails, make calls, or play music. Screen control must be switched on by the user (Accessibility settings) and is best-effort — it can miss a control that has no readable label. If asked for something you cannot do, say so briefly and honestly — never pretend, and never claim to have done something you did not actually do.

        Calendar commands (output only after the user confirms, each on its own line, never read them aloud):
        add -> <<CAL|ADD|Title|YYYY-MM-DD|HH:MM|60>> (24-hour time; last field is duration in minutes)
        delete/cancel -> <<CAL|DEL|Title|YYYY-MM-DD|HH:MM>>
        To reschedule or move an event, output a DEL for its current time and an ADD for the new time. To cancel, output only a DEL. Use the provided upcoming events to identify the exact event. Only tell the user you added, removed, or rescheduled something if you actually output the matching command.

        Screen commands (only when the user clearly asks you to open an app or tap something on screen; each on its own line, never read them aloud):
        open an app -> <<OPEN|AppName>>
        tap a visible control -> <<TAP|VisibleLabel>>
        To open an app and then tap inside it, output <<OPEN|AppName>> then <<TAP|Label>>. For the tap label use the exact short on-screen name of the target (e.g. a contact's name like "Mom"), not a whole phrase. A <<TAP|..>> automatically scrolls to find the target if it's below the fold, so if the user asks you to scroll to, find, or open something like a chat, just output the tap — never say you cannot scroll. Only claim you opened or tapped something if you actually output the matching command.

        After finishing a task, briefly confirm it and ask if there's anything else. When the user is done (e.g. "no", "that's all", "thanks"), give a short sign-off and append <<END>> on its own line (never read it aloud).
    """.trimIndent()

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
