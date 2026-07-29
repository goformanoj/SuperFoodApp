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
     * Models the provider has retired. Unlike a rate limit this never clears, so
     * they are dropped for the life of the process rather than cooled down.
     */
    private val retired = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Preference order per tier. Quotas are per model, so putting the small model
     * first for commands preserves the big model's daily allowance for the turns
     * that actually need it — and each list still ends with the others, so a
     * model out of quota is never a dead end.
     */
    private val SMART_MODELS = listOf(
        "llama-3.3-70b-versatile",
        "openai/gpt-oss-120b",
        "llama-3.1-8b-instant",
    )

    private val FAST_MODELS = listOf(
        "llama-3.1-8b-instant",
        "openai/gpt-oss-20b",
        "llama-3.3-70b-versatile",
    )

    private fun modelsFor(tier: Tier) = if (tier == Tier.FAST) FAST_MODELS else SMART_MODELS

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

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
                if (model in retired) continue
                if (blockedUntil.getOrDefault(model, 0L) > System.currentTimeMillis()) continue
                anyTried = true
                val outcome = requestModel(model, messages, context, key, systemOverride)
                if (outcome.text != null) return@withContext outcome.text
                lastReason = outcome.reason ?: "Unknown error"
                if (!outcome.retryable) throw GroqException(lastReason)
            }
            if (!anyTried) {
                if (retired.size >= modelsFor(tier).size) {
                    throw GroqException("No Groq model is available — all of them have been retired")
                }
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
        When asked for a SONG or a VIDEO, prefer a single track or video over a playlist, album,
        artist page or "mix" — those open a list instead of playing the thing that was asked for.
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
                // Anything wrong with THIS model must fall through to the next
                // one, never abort the chain. A device screenshot showed
                // gemma2-9b-it returning 400 "decommissioned" and killing the
                // whole request, even though a working model was next in line.
                val gone = code == 404 || (code == 400 && RateLimit.isRetiredModel(body))
                if (gone) {
                    retired.add(model)
                    DebugLog.log(DebugLog.Stage.ERROR, "model $model is retired — dropping it")
                }
                Outcome(null, reason, retryable = gone || code == 429)
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
