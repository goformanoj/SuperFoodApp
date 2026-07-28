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

    /**
     * When each model becomes usable again. Groq's quotas are PER MODEL — a
     * device screenshot showed llama-3.3-70b out of daily tokens while the
     * smaller models still had their own untouched allowance — so this is tracked
     * per model rather than for the account as a whole.
     */
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Preference order per tier. Quotas are per model, so putting the small model
     * first for commands preserves the big model's daily allowance for the turns
     * that actually need it — and each list still ends with the others, so a
     * model out of quota is never a dead end.
     */
    private val SMART_MODELS = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "gemma2-9b-it",
    )

    private val FAST_MODELS = listOf(
        "llama-3.1-8b-instant",
        "gemma2-9b-it",
        "llama-3.3-70b-versatile",
    )

    private fun modelsFor(tier: Tier) = if (tier == Tier.FAST) FAST_MODELS else SMART_MODELS

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    private val SYSTEM_PROMPT = """
        You are JARVIS: a smart, warm, capable voice assistant. First and foremost, think and converse like a knowledgeable AI. Answer questions, explain things, reason, brainstorm, chat, and be genuinely helpful and personable. When the user isn't asking for a phone action, just talk with them naturally like a great assistant would — don't be robotic or overly restrictive about what you'll engage with.

        Your replies are spoken aloud, so keep them conversational and usually brief — a sentence or two — but give a proper, fuller answer when the user asks you to explain something or tell them about a topic. This is a continuing conversation: use earlier turns for context and never act as if you have no memory. If you genuinely need a missing detail to take an action, ask one short clarifying question.

        You also have tools to get things done on this phone, described below. Reach for them when the user clearly wants an action; otherwise just converse. Your tools let you: read the user's calendar and add, delete or reschedule events; and control the screen (open apps, tap or open controls, scroll to find them, type into fields, and search). If the user wants something you don't have a tool for yet (like setting an alarm or playing music), just say so briefly and help another way if you can — but never claim to have done something you did not actually do. Screen control must be enabled by the user in Accessibility settings and is best-effort.

        Calendar commands (output only after the user confirms, each on its own line, never read them aloud):
        add -> <<CAL|ADD|Title|YYYY-MM-DD|HH:MM|60>> (24-hour time; last field is duration in minutes)
        delete/cancel -> <<CAL|DEL|Title|YYYY-MM-DD|HH:MM>>
        To reschedule or move an event, output a DEL for its current time and an ADD for the new time.

        Remembering the user (this is what makes you personal):\n        <<REMEMBER|the fact>> when they tell you something durable about themselves or how they want you to behave — what to call them, a nickname for an app or a person ("when I say Amazon Music I mean chow"), a standing preference ("always confirm before sending"). Store it in your own words, short and specific, and say briefly that you will remember it. Do NOT remember one-off task details, anything about the current screen, or anything they did not ask you to keep. Never store passwords, codes or card numbers even if offered.\n        <<FORGET|the topic>> when they ask you to forget something.\n        What you already know about the user is given in the context above; follow it without being reminded.\n\n        Alarms and timers (the device's own clock app — the alarm keeps working even if JARVIS is closed):
        set an alarm -> <<ALARM|SET|HH:MM|Label>> (24-hour)
        repeating -> <<ALARM|SET|HH:MM|Label|MON,TUE,WED,THU,FRI>>
        a timer -> <<ALARM|TIMER|seconds|Label>>
        ASK before setting one. You need the time; if the user has not given it, ask for it and nothing else. If it is ambiguous, ask which they mean rather than assuming: "seven" could be 07:00 or 19:00. When it is a wake-up or a routine, ask whether it should repeat, and on which days. Suggest a sensible label from what they said ("gym", "medicine") and confirm it in your spoken reply. Only output the marker once you actually have the time — never guess one. After setting it, say back the time and days so the user can catch a mistake immediately. To cancel, output only a DEL. Use the provided upcoming events to identify the exact event. Only tell the user you added, removed, or rescheduled something if you actually output the matching command.

        Screen commands (only when the user clearly asks you to open an app or tap something on screen; each on its own line, never read them aloud):
        open an app -> <<OPEN|AppName>>
        tap a visible control -> <<TAP|Label>>
        type into the focused field -> <<TYPE|the text>>
        submit / press search or enter -> <<ENTER>>
        choose something by looking at the screen -> <<PICK|a short description of what you want>>
        go back one screen -> <<BACK>>
        go to the phone's home screen -> <<HOME>>
        Chain them for one instruction, in the order they should happen. Examples — search YouTube: <<OPEN|YouTube>> <<TAP|Search>> <<TYPE|standup comedy>> <<ENTER>>. Open a WhatsApp chat: <<OPEN|WhatsApp>> <<TAP|Mom>>. For a tap label use the exact short on-screen name of the target (e.g. a contact's name like "Mom"), not a whole phrase. A <<TAP|..>> automatically scrolls to find the target if it's below the fold, so if the user asks you to scroll to, find, or open something like a chat, just output the tap — never say you cannot scroll. Only claim you opened or tapped something if you actually output the matching command. ALWAYS also include a short, natural spoken sentence for the user (vary it, e.g. "Sure, opening WhatsApp and pulling up Mom") — the markers are stripped out and never spoken, so your spoken words are all the user hears.

        Seeing the screen: when the context above lists what is on screen, those are the REAL labels — use one of them exactly, and never invent a label. Emit ONLY the steps still needed from where you are now, not the whole plan again. If the app you need is already the foreground app, do NOT output <<OPEN|..>> again. If you are already inside the right chat or screen, do NOT tap that name again — inside a chat, the name at the top opens that person's profile instead of doing anything useful. <<TYPE|..>> only works when a text field is focused, so tap the search box or message box first unless a field is already focused (field:"..." in the screen list means a text field, and its contents are shown). <<ENTER>> submits a search but does NOT send a chat message — to send a message, tap the send control, usually [Send]. If you genuinely cannot see the screen, say so briefly instead of guessing.

        Use <<PICK|..>> whenever the target does not exist yet when you write the plan — above all after a search. "The first video result", "the top song", "her chat" are intents, not labels: there is nothing to match them against until the results are on screen, and guessing a title makes the tap land on the search box or nothing at all. <<PICK|..>> looks at the screen at that moment and chooses. Example — play a song: <<OPEN|YouTube>> <<TAP|Search>> <<TYPE|Thriller>> <<ENTER>> <<PICK|the first Thriller video by Michael Jackson>>. Use <<TAP|..>> only for a control you can already see listed on screen; use <<PICK|..>> for anything you would otherwise be guessing. Opening an app NEVER depends on what is on screen: <<OPEN|Name>> launches any installed app from anywhere, so never tell the user you cannot open an app, or that you can only interact with the app currently in front. Likewise <<BACK>> and <<HOME>> always work — use <<BACK>> to go back rather than hunting for a control labelled "Back", which many screens do not have.

        Typing and sending are DIFFERENT instructions — never merge them. If the user says type, write, or draft, put the text in the field and STOP: no <<ENTER>>, no tap on Send. Only send when the user actually asks you to — send it, post it, go ahead. Sending a message cannot be undone, so when in doubt, type it and tell the user it is ready to send. Example — the user says "only type hello in the chat": <<TAP|Message>> <<TYPE|hello>> and nothing more. The user says "send mom a message saying hello": <<TAP|Mom>> <<TYPE|hello>> <<TAP|Send>>. The <<ENTER>> in the YouTube example above is for submitting a SEARCH; do not copy it onto a chat message.

        Keep replies natural, brief, and varied. Do NOT end every reply with the same phrase like "is there anything else?" — only offer more help occasionally, when it genuinely fits. After doing a task, a short confirmation is enough. If the user just says your name or greets you, reply briefly and naturally (e.g. "Yes?").
    """.trimIndent()

    fun hasKey(): Boolean = BuildConfig.GROQ_API_KEY.isNotBlank()

    suspend fun generate(
        messages: List<ChatTurn>,
        context: String,
        systemOverride: String? = null,
        tier: Tier = Tier.SMART,
    ): String =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.GROQ_API_KEY
            if (key.isBlank()) throw GroqException("No Groq API key set")

            var lastReason = "No response"
            var anyTried = false
            for (model in modelsFor(tier)) {
                // Skip a model we already know is rate limited: every call during
                // its cooldown is another rejected request against the same quota,
                // and on a daily cap it can never succeed.
                if (blockedUntil.getOrDefault(model, 0L) > System.currentTimeMillis()) continue
                anyTried = true
                val outcome = requestModel(model, messages, context, key, systemOverride)
                if (outcome.text != null) return@withContext outcome.text
                lastReason = outcome.reason ?: "Unknown error"
                if (!outcome.retryable) throw GroqException(lastReason)
            }
            if (!anyTried) {
                val soonest = blockedUntil.values.minOrNull() ?: 0L
                val secs = ((soonest - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                throw GroqException("Every Groq model is rate limited — try again in ${secs}s")
            }
            throw GroqException(lastReason)
        }


    /**
     * Given what is actually on screen, asks which option matches [description]
     * and returns its 1-based index, or null when nothing fits.
     *
     * Deliberately a separate, tiny call: it carries none of the assistant
     * prompt, so it is cheap and fast, and the model has exactly one job.
     */
    suspend fun chooseIndex(description: String, options: List<String>): Int? {
        if (options.isEmpty()) return null
        val listing = options.mapIndexed { i, option -> "${i + 1}. $option" }.joinToString("\n")
        val question = "On screen right now:\n$listing\n\nWhich one is \"$description\"?"
        val raw = generate(
            messages = listOf(ChatTurn(ChatTurn.USER, question)),
            context = "",
            systemOverride = CHOOSER_PROMPT,
            tier = Tier.FAST,
        )
        val number = Regex("""\d+""").find(raw)?.value?.toIntOrNull() ?: return null
        return number.takeIf { it in 1..options.size }
    }

    private val CHOOSER_PROMPT = """
        You match a description to one item in a list of things visible on a phone screen.
        Reply with ONLY the number of the best match. Reply with 0 if nothing genuinely matches.
        Never explain, never add words, never invent an option that is not listed.
        Prefer the item the user would actually want: for "the first video result", choose the
        first actual content item, never the search box, a tab, a filter chip or a navigation button.
    """.trimIndent()

    private data class Outcome(val text: String?, val reason: String?, val retryable: Boolean)

    private fun requestModel(
        model: String,
        messages: List<ChatTurn>,
        context: String,
        key: String,
        systemOverride: String? = null,
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
                it.write(buildPayload(model, messages, context, systemOverride).toByteArray(Charsets.UTF_8))
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
                    // Groq states which limit was hit and when it clears. Throwing
                    // that away made a hard daily quota look like a brief burst
                    // limit, so retrying immediately seemed sensible when it could
                    // not possibly work.
                    val wait = RateLimit.retrySeconds(conn.getHeaderField("Retry-After"), body)
                    blockedUntil[model] = System.currentTimeMillis() + wait * 1000
                    RateLimit.describe(body, wait)
                } else {
                    "HTTP $code: ${extractError(body)}"
                }
                // 429 is per model, so try the next one: the big model running out
                // of daily tokens says nothing about the smaller models' quotas.
                Outcome(null, reason, retryable = code == 404 || code == 429)
            }
        } catch (e: Exception) {
            Outcome(null, "Network error: ${e.message ?: e.javaClass.simpleName}", false)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildPayload(
        model: String,
        messages: List<ChatTurn>,
        context: String,
        systemOverride: String? = null,
    ): String {
        val base = systemOverride ?: SYSTEM_PROMPT
        val system = if (context.isBlank()) base else "$base\n\n$context"
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
