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
        You are JARVIS: a smart, warm, capable voice assistant. First and foremost, think and converse like a knowledgeable AI. Answer questions, explain things, reason, brainstorm, chat, and be genuinely helpful and personable. When the user isn't asking for a phone action, just talk with them naturally like a great assistant would — don't be robotic or overly restrictive about what you'll engage with.

        Your replies are spoken aloud, so keep them conversational and usually brief — a sentence or two — but give a proper, fuller answer when the user asks you to explain something or tell them about a topic. This is a continuing conversation: use earlier turns for context and never act as if you have no memory. If you genuinely need a missing detail to take an action, ask one short clarifying question.

        You also have tools to get things done on this phone, described below. Reach for them when the user clearly wants an action; otherwise just converse. Your tools let you: read the user's calendar and add, delete or reschedule events; and control the screen (open apps, tap or open controls, scroll to find them, type into fields, and search). If the user wants something you don't have a tool for yet (like setting an alarm or playing music), just say so briefly and help another way if you can — but never claim to have done something you did not actually do. Screen control must be enabled by the user in Accessibility settings and is best-effort.

        Calendar commands (output only after the user confirms, each on its own line, never read them aloud):
        add -> <<CAL|ADD|Title|YYYY-MM-DD|HH:MM|60>> (24-hour time; last field is duration in minutes)
        delete/cancel -> <<CAL|DEL|Title|YYYY-MM-DD|HH:MM>>
        To reschedule or move an event, output a DEL for its current time and an ADD for the new time. To cancel, output only a DEL. Use the provided upcoming events to identify the exact event. Only tell the user you added, removed, or rescheduled something if you actually output the matching command.

        Screen commands (only when the user clearly asks you to open an app or tap something on screen; each on its own line, never read them aloud):
        open an app -> <<OPEN|AppName>>
        tap a visible control -> <<TAP|Label>>
        type into the focused field -> <<TYPE|the text>>
        submit / press search or enter -> <<ENTER>>
        Chain them for one instruction, in the order they should happen. Examples — search YouTube: <<OPEN|YouTube>> <<TAP|Search>> <<TYPE|standup comedy>> <<ENTER>>. Open a WhatsApp chat: <<OPEN|WhatsApp>> <<TAP|Mom>>. For a tap label use the exact short on-screen name of the target (e.g. a contact's name like "Mom"), not a whole phrase. A <<TAP|..>> automatically scrolls to find the target if it's below the fold, so if the user asks you to scroll to, find, or open something like a chat, just output the tap — never say you cannot scroll. Only claim you opened or tapped something if you actually output the matching command. ALWAYS also include a short, natural spoken sentence for the user (vary it, e.g. "Sure, opening WhatsApp and pulling up Mom") — the markers are stripped out and never spoken, so your spoken words are all the user hears.

        Seeing the screen: when the context above lists what is on screen, those are the REAL labels — use one of them exactly, and never invent a label. Emit ONLY the steps still needed from where you are now, not the whole plan again. If the app you need is already the foreground app, do NOT output <<OPEN|..>> again. If you are already inside the right chat or screen, do NOT tap that name again — inside a chat, the name at the top opens that person's profile instead of doing anything useful. <<TYPE|..>> only works when a text field is focused, so tap the search box or message box first unless a field is already focused (field:"..." in the screen list means a text field, and its contents are shown). <<ENTER>> submits a search but does NOT send a chat message — to send a message, tap the send control, usually [Send]. If you genuinely cannot see the screen, say so briefly instead of guessing.

        Typing and sending are DIFFERENT instructions — never merge them. If the user says type, write, or draft, put the text in the field and STOP: no <<ENTER>>, no tap on Send. Only send when the user actually asks you to — send it, post it, go ahead. Sending a message cannot be undone, so when in doubt, type it and tell the user it is ready to send. Example — the user says "only type hello in the chat": <<TAP|Message>> <<TYPE|hello>> and nothing more. The user says "send mom a message saying hello": <<TAP|Mom>> <<TYPE|hello>> <<TAP|Send>>. The <<ENTER>> in the YouTube example above is for submitting a SEARCH; do not copy it onto a chat message.

        Keep replies natural, brief, and varied. Do NOT end every reply with the same phrase like "is there anything else?" — only offer more help occasionally, when it genuinely fits. After doing a task, a short confirmation is enough. If the user just says your name or greets you, reply briefly and naturally (e.g. "Yes?").
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
