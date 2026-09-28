package com.jarvis.os.desktop.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64

/**
 * Google Calendar and Gmail over their REST APIs, with the connected account's token
 * (AGENT_PLAN Phase 6, S2 with the calendar, S8 "draft a reply"). Everything that turns
 * JSON or MIME into something the agent uses is pure and tested (GoogleApisTest).
 *
 * Nothing here sends mail by itself: [Gmail.sendDraft] is only reachable through the
 * agent's send_draft tool, which is IRREVERSIBLE — no Approve click, no send.
 */
class GoogleApis(private val account: GoogleAccount) : com.jarvis.os.desktop.agent.ToolBox.Google {

    override val connected: Boolean get() = account.connected

    data class Event(val id: String, val title: String, val start: String, val end: String?, val allDay: Boolean, val location: String?)
    data class MailSummary(val id: String, val threadId: String, val from: String, val subject: String, val date: String, val snippet: String)
    data class Mail(
        val id: String, val threadId: String, val from: String, val replyTo: String?, val to: String, val subject: String,
        val date: String, val messageId: String?, val references: String?, val body: String,
    )
    data class Draft(val id: String, val to: String, val subject: String, val body: String)

    // ── Calendar ─────────────────────────────────────────────────────────────

    override suspend fun events(from: ZonedDateTime, to: ZonedDateTime): List<Event> {
        val q = "timeMin=" + enc(from.toOffsetDateTime().toString()) + "&timeMax=" + enc(to.toOffsetDateTime().toString()) +
            "&singleEvents=true&orderBy=startTime&maxResults=50"
        return parseEvents(get("$CAL/calendars/primary/events?$q"))
    }

    /** Adds an event on the user's primary calendar (no guests, so no invitations go out). */
    override suspend fun addEvent(title: String, start: ZonedDateTime, end: ZonedDateTime, location: String?, notes: String?): Event {
        val body = eventBody(title, start, end, location, notes)
        return parseEvents(JSONObject().put("items", JSONArray().put(JSONObject(send("POST", "$CAL/calendars/primary/events", body.toString())))).toString()).single()
    }

    override suspend fun deleteEvent(id: String) { send("DELETE", "$CAL/calendars/primary/events/${enc(id)}", null) }

    // ── Gmail ────────────────────────────────────────────────────────────────

    /** Gmail's own search syntax ("from:landlord newer_than:30d"). */
    override suspend fun searchMail(query: String, max: Int): List<MailSummary> {
        val list = JSONObject(get("$GMAIL/messages?q=${enc(query)}&maxResults=${max.coerceIn(1, 20)}"))
        val ids = list.optJSONArray("messages") ?: return emptyList()
        return (0 until ids.length()).map { i ->
            val id = ids.getJSONObject(i).getString("id")
            parseSummary(get("$GMAIL/messages/${enc(id)}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"))
        }
    }

    override suspend fun readMail(id: String): Mail = parseMail(get("$GMAIL/messages/${enc(id)}?format=full"))

    /** A draft in the user's Drafts folder; threaded as a reply when [replyTo] is given. Nothing is sent. */
    override suspend fun createDraft(to: String, subject: String, body: String, replyTo: Mail?): Draft {
        val raw = mime(to, subject, body, inReplyTo = replyTo?.messageId, references = listOfNotNull(replyTo?.references, replyTo?.messageId).joinToString(" ").ifBlank { null })
        val msg = JSONObject().put("raw", raw).apply { replyTo?.let { put("threadId", it.threadId) } }
        val res = JSONObject(send("POST", "$GMAIL/drafts", JSONObject().put("message", msg).toString()))
        return Draft(res.getString("id"), to, subject, body)
    }

    override suspend fun deleteDraft(id: String) { send("DELETE", "$GMAIL/drafts/${enc(id)}", null) }

    /** Sends a draft. Only ever called after the user approved it (ToolBox: IRREVERSIBLE). */
    override suspend fun sendDraft(id: String) { send("POST", "$GMAIL/drafts/send", JSONObject().put("id", id).toString()) }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private suspend fun get(url: String): String = send("GET", url, null)

    private suspend fun send(method: String, url: String, json: String?): String {
        val token = account.accessToken()
        return withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                if (json != null) conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException(errorMessage(code, body))
                body
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        private const val CAL = "https://www.googleapis.com/calendar/v3"
        private const val GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** A sentence for the user out of a Google API error. Pure; tested. */
        fun errorMessage(code: Int, body: String): String {
            val reason = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
            return when {
                code == 401 -> "Google access expired — connect again in Settings."
                code == 403 && reason.contains("has not been used", true) -> "That Google API isn't turned on for this app yet (Google Cloud console → APIs)."
                code == 403 -> "Google didn't allow that (${reason.ifBlank { "permission denied" }})."
                code == 404 -> "Google couldn't find that."
                code == 429 -> "Google is rate-limiting — try again in a minute."
                else -> "Google failed (HTTP $code)."
            }
        }

        fun parseEvents(body: String): List<Event> {
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val e = items.optJSONObject(i) ?: return@mapNotNull null
                if (e.optString("status") == "cancelled") return@mapNotNull null
                val start = e.optJSONObject("start")
                val end = e.optJSONObject("end")
                val allDay = start?.has("date") == true && start.optString("dateTime").isBlank()
                Event(
                    e.optString("id"), e.optString("summary").ifBlank { "(no title)" },
                    start?.optString("dateTime")?.ifBlank { null } ?: start?.optString("date").orEmpty(),
                    end?.optString("dateTime")?.ifBlank { null } ?: end?.optString("date"),
                    allDay, e.optString("location").ifBlank { null },
                )
            }
        }

        fun eventBody(title: String, start: ZonedDateTime, end: ZonedDateTime, location: String?, notes: String?): JSONObject =
            JSONObject().put("summary", title)
                .put("start", JSONObject().put("dateTime", start.toOffsetDateTime().toString()).put("timeZone", start.zone.id))
                .put("end", JSONObject().put("dateTime", end.toOffsetDateTime().toString()).put("timeZone", end.zone.id))
                .apply { location?.let { put("location", it) }; notes?.let { put("description", it) } }

        private fun headers(payload: JSONObject?): Map<String, String> {
            val arr = payload?.optJSONArray("headers") ?: return emptyMap()
            return (0 until arr.length()).associate { i ->
                val h = arr.getJSONObject(i)
                h.optString("name").lowercase() to h.optString("value")
            }
        }

        fun parseSummary(body: String): MailSummary {
            val o = JSONObject(body)
            val h = headers(o.optJSONObject("payload"))
            return MailSummary(o.optString("id"), o.optString("threadId"), h["from"].orEmpty(), h["subject"].orEmpty(), h["date"].orEmpty(), o.optString("snippet"))
        }

        fun parseMail(body: String): Mail {
            val o = JSONObject(body)
            val p = o.optJSONObject("payload")
            val h = headers(p)
            return Mail(
                o.optString("id"), o.optString("threadId"), h["from"].orEmpty(), h["reply-to"], h["to"].orEmpty(), h["subject"].orEmpty(),
                h["date"].orEmpty(), h["message-id"], h["references"], textOf(p).trim().take(12_000),
            )
        }

        /** The readable text of a MIME tree: text/plain preferred, else text/html without tags. */
        fun textOf(part: JSONObject?): String {
            if (part == null) return ""
            fun find(p: JSONObject, type: String): String? {
                if (p.optString("mimeType").equals(type, true)) p.optJSONObject("body")?.optString("data")?.takeIf { it.isNotBlank() }?.let { return decode(it) }
                val parts = p.optJSONArray("parts") ?: return null
                for (i in 0 until parts.length()) find(parts.getJSONObject(i), type)?.let { return it }
                return null
            }
            find(part, "text/plain")?.let { return it }
            find(part, "text/html")?.let { html ->
                return html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                    .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n").replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")
                    .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n")
            }
            return ""
        }

        private fun decode(b64url: String): String = String(Base64.getUrlDecoder().decode(b64url.replace("=", "").let { it.padEnd((it.length + 3) / 4 * 4, '=') }), Charsets.UTF_8)

        /**
         * An RFC 2822 message for Gmail's `raw`: UTF-8 subject (RFC 2047), base64 body, reply
         * threading headers. Header values have CR/LF removed so no text can smuggle in a
         * header (e.g. a Bcc). Pure; tested.
         */
        fun mime(to: String, subject: String, body: String, inReplyTo: String? = null, references: String? = null): String {
            fun clean(s: String) = s.replace(Regex("[\\r\\n]+"), " ").trim()
            val subj = clean(subject)
            val encodedSubject = if (subj.all { it.code in 32..126 }) subj else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subj.toByteArray(Charsets.UTF_8)) + "?="
            val lines = mutableListOf(
                "To: " + clean(to),
                "Subject: $encodedSubject",
                "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=UTF-8",
                "Content-Transfer-Encoding: base64",
            )
            inReplyTo?.let { lines += "In-Reply-To: " + clean(it) }
            references?.let { lines += "References: " + clean(it) }
            val b64 = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
            val msg = lines.joinToString("\r\n") + "\r\n\r\n" + b64
            return Base64.getUrlEncoder().withoutPadding().encodeToString(msg.toByteArray(Charsets.UTF_8))
        }

        /** "Re: " once, never "Re: Re: ". */
        fun replySubject(original: String): String = if (original.trim().startsWith("re:", ignoreCase = true)) original.trim() else "Re: ${original.trim()}"

        /** Who a reply goes to: Reply-To if the sender set one, else From. */
        fun replyRecipient(m: Mail): String = m.replyTo?.takeIf { it.isNotBlank() } ?: m.from

        /** "a@b.com", "Name <a@b.com>", or a comma list of those. Pure; tested. */
        fun looksLikeRecipients(s: String): Boolean {
            val parts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val addr = Regex("""^(?:[^<>\r\n]*<)?[^\s@<>,]+@[^\s@<>,]+\.[^\s@<>,]+>?$""")
            return parts.isNotEmpty() && parts.size <= 20 && parts.all { addr.matches(it) }
        }

        /** An event's start as the model reads it: "Tue 29 Sep 10:00" or "Tue 29 Sep (all day)". */
        fun label(e: Event, zone: ZoneId): String = runCatching {
            if (e.allDay) LocalDate.parse(e.start).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)) + " (all day)"
            else ZonedDateTime.parse(e.start).withZoneSameInstant(zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.ENGLISH))
        }.getOrDefault(e.start)

        fun instant(s: String): Instant? = runCatching { ZonedDateTime.parse(s).toInstant() }.getOrNull()
    }
}
