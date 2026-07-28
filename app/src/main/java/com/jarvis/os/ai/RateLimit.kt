package com.jarvis.os.ai

/**
 * Understands a provider's "you are rate limited" reply.
 *
 * Groq says exactly which limit was hit and when it clears — which limit
 * (requests or tokens, per minute or per day), how much was used, and how long
 * to wait. The app used to throw all of that away and print "wait a moment",
 * which is why a hard daily cap looked identical to a brief burst limit, and why
 * retrying immediately seemed reasonable when it could not possibly work.
 *
 * Pure text handling, so the parsing is unit-tested.
 */
object RateLimit {

    /** Fallback wait when the provider does not say. */
    const val DEFAULT_COOLDOWN_SECONDS = 20L

    /** Never sit out longer than this, however large the stated wait. */
    const val MAX_COOLDOWN_SECONDS = 15L * 60

    private val RETRY_PHRASE = Regex("""try again in\s+([0-9hms.\s]+)""", RegexOption.IGNORE_CASE)
    private val PART = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([hms])""", RegexOption.IGNORE_CASE)

    /**
     * How long to wait before trying again, from the `Retry-After` header if
     * present, otherwise from the message body. Clamped to something sane.
     */
    fun retrySeconds(retryAfterHeader: String?, body: String): Long {
        retryAfterHeader?.trim()?.toDoubleOrNull()?.let { header ->
            if (header > 0) return header.toLong().coerceAtMost(MAX_COOLDOWN_SECONDS)
        }
        val phrase = RETRY_PHRASE.find(body)?.groupValues?.get(1)
        if (phrase != null) {
            var total = 0.0
            PART.findAll(phrase).forEach { part ->
                val value = part.groupValues[1].toDoubleOrNull() ?: 0.0
                total += when (part.groupValues[2].lowercase()) {
                    "h" -> value * 3600
                    "m" -> value * 60
                    else -> value
                }
            }
            // Round up: coming back a fraction early just earns another 429.
            if (total > 0) return Math.ceil(total).toLong().coerceAtMost(MAX_COOLDOWN_SECONDS)
        }
        return DEFAULT_COOLDOWN_SECONDS
    }

    /**
     * A message worth showing. Prefers the provider's own wording, because
     * "tokens per day" and "requests per minute" call for completely different
     * reactions from the user.
     */
    fun describe(body: String, seconds: Long): String {
        val detail = extractMessage(body)
        val wait = if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
        return if (detail.isBlank()) {
            "Rate limited by Groq. Retrying is blocked for $wait."
        } else {
            "$detail (waiting $wait)"
        }
    }


    /**
     * A short sentence for the orb, where the provider's full paragraph does not
     * fit and reads as noise. The complete message still goes to the trace.
     */
    fun shortSummary(body: String, seconds: Long): String {
        val wait = if (seconds >= 60) "${seconds / 60} minutes" else "$seconds seconds"
        return if (isDailyQuota(body)) {
            "Today's AI quota is used up. It resets in about $wait."
        } else {
            "Too many requests just now — try again in $wait."
        }
    }


    /**
     * True when the provider says this model no longer exists. Groq reports a
     * retired model as HTTP 400, not 404, so matching on the status alone missed
     * it and the whole request died instead of trying the next model.
     */
    fun isRetiredModel(body: String): Boolean =
        body.contains("decommissioned", ignoreCase = true) ||
            body.contains("no longer supported", ignoreCase = true) ||
            body.contains("does not exist", ignoreCase = true) ||
            body.contains("has been deprecated", ignoreCase = true)

    /** True when the limit is a daily quota — waiting seconds will not help. */
    fun isDailyQuota(body: String): Boolean =
        body.contains("per day", ignoreCase = true) || body.contains("TPD", ignoreCase = false) ||
            body.contains("RPD", ignoreCase = false)

    private fun extractMessage(body: String): String {
        val marker = "\"message\""
        val at = body.indexOf(marker)
        if (at == -1) return body.take(200).trim()
        val start = body.indexOf('"', body.indexOf(':', at) + 1)
        if (start == -1) return body.take(200).trim()
        val sb = StringBuilder()
        var i = start + 1
        while (i < body.length && body[i] != '"') {
            if (body[i] == '\\' && i + 1 < body.length) i++
            sb.append(body[i])
            i++
        }
        return sb.toString().take(240).trim()
    }
}
