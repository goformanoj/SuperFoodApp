package com.jarvis.os.desktop.google

import com.jarvis.os.desktop.GoogleSignIn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64

class GoogleApisTest {

    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    // ── Calendar ──

    @Test
    fun eventsParseTimedAllDayAndDropCancelled() {
        val body = """{"items":[
            {"id":"e1","summary":"Design review","start":{"dateTime":"2026-09-29T11:00:00+05:30"},"end":{"dateTime":"2026-09-29T12:00:00+05:30"},"location":"Room 4"},
            {"id":"e2","summary":"Holiday","start":{"date":"2026-10-02"},"end":{"date":"2026-10-03"}},
            {"id":"e3","status":"cancelled","summary":"Gone","start":{"dateTime":"2026-09-29T09:00:00Z"}},
            {"id":"e4","start":{"dateTime":"2026-09-29T15:00:00Z"}}]}"""
        val ev = GoogleApis.parseEvents(body)
        assertEquals(listOf("e1", "e2", "e4"), ev.map { it.id })
        assertEquals("Tue 29 Sep 11:00", GoogleApis.label(ev[0], zone))
        assertEquals("Room 4", ev[0].location)
        assertTrue(ev[1].allDay)
        assertEquals("Fri 2 Oct (all day)", GoogleApis.label(ev[1], zone))
        assertEquals("(no title)", ev[2].title)
        // 15:00 UTC is 20:30 in India.
        assertEquals("Tue 29 Sep 20:30", GoogleApis.label(ev[2], zone))
        assertTrue(GoogleApis.parseEvents("{}").isEmpty())
    }

    @Test
    fun anEventIsWrittenWithItsZoneAndNoGuests() {
        val start = ZonedDateTime.of(2026, 9, 29, 11, 0, 0, 0, zone)
        val o = GoogleApis.eventBody("Review", start, start.plusHours(1), "Room 4", null)
        assertEquals("2026-09-29T11:00+05:30", o.getJSONObject("start").getString("dateTime"))
        assertEquals("Asia/Kolkata", o.getJSONObject("end").getString("timeZone"))
        assertFalse(o.has("attendees"))
        assertFalse(o.has("description"))
    }

    // ── Gmail ──

    @Test
    fun aMessageIsReadPreferringPlainTextAndKeepingThreadHeaders() {
        val msg = JSONObject("""{"id":"m1","threadId":"t1","payload":{"mimeType":"multipart/alternative","headers":[
            {"name":"From","value":"Landlord <ll@example.com>"},{"name":"Reply-To","value":"rent@example.com"},
            {"name":"Subject","value":"Rent for October"},{"name":"Message-ID","value":"<abc@mail>"},{"name":"References","value":"<zz@mail>"}],
            "parts":[{"mimeType":"text/html","body":{"data":"${b64("<p>HTML</p>")}"}},{"mimeType":"text/plain","body":{"data":"${b64("Hi, rent is due Friday.\nThanks")}"}}]}}""").toString()
        val m = GoogleApis.parseMail(msg)
        assertEquals("Hi, rent is due Friday.\nThanks", m.body)
        assertEquals("<abc@mail>", m.messageId)
        assertEquals("rent@example.com", GoogleApis.replyRecipient(m))
        assertEquals("Re: Rent for October", GoogleApis.replySubject(m.subject))
        assertEquals("Re: Rent", GoogleApis.replySubject("RE: Rent".replace("RE", "Re")))
        assertEquals("RE: x", GoogleApis.replySubject("RE: x"))
    }

    @Test
    fun htmlOnlyMailBecomesText() {
        val p = JSONObject("""{"mimeType":"text/html","body":{"data":"${b64("<style>p{}</style><p>Hello &amp; welcome</p><br>Line two")}"}}""")
        assertEquals("Hello & welcome\nLine two", GoogleApis.textOf(p).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
    }

    @Test
    fun theDraftIsWellFormedUtf8AndHeadersCannotBeSmuggled() {
        val raw = GoogleApis.mime("ll@example.com", "Rent — paying Friday\r\nBcc: evil@x.com", "Hi,\nI'll pay on Friday.\n— Pranjal", "<abc@mail>", "<zz@mail> <abc@mail>")
        val msg = String(Base64.getUrlDecoder().decode(raw))
        val (head, body) = msg.split("\r\n\r\n", limit = 2)
        val headers = head.split("\r\n")
        assertTrue(headers.contains("To: ll@example.com"))
        // No injected header line: the CR/LF in the subject was flattened into it.
        assertTrue(headers.none { it.startsWith("Bcc:") })
        val subject = headers.single { it.startsWith("Subject: ") }.removePrefix("Subject: ")
        assertTrue(subject.startsWith("=?UTF-8?B?"))
        assertEquals("Rent — paying Friday Bcc: evil@x.com", String(Base64.getDecoder().decode(subject.removePrefix("=?UTF-8?B?").removeSuffix("?="))))
        assertTrue(headers.contains("In-Reply-To: <abc@mail>"))
        assertEquals("Hi,\r\nI'll pay on Friday.\r\n— Pranjal", String(Base64.getMimeDecoder().decode(body)))
    }

    @Test
    fun recipientsAreCheckedBeforeADraftIsMade() {
        assertTrue(GoogleApis.looksLikeRecipients("ll@example.com"))
        assertTrue(GoogleApis.looksLikeRecipients("Landlord <ll@example.com>, b@c.in"))
        assertFalse(GoogleApis.looksLikeRecipients("the landlord"))
        assertFalse(GoogleApis.looksLikeRecipients(""))
        assertFalse(GoogleApis.looksLikeRecipients("a@b.com\r\nBcc: x@y.com"))
    }

    @Test
    fun googleErrorsBecomeSentences() {
        assertTrue(GoogleApis.errorMessage(401, "").contains("connect again"))
        assertTrue(GoogleApis.errorMessage(403, """{"error":{"message":"Gmail API has not been used in project 1"}}""").contains("isn't turned on"))
        assertTrue(GoogleApis.errorMessage(429, "").contains("rate-limiting"))
    }

    // ── the account: sealed at rest, refresh token required ──

    private val fakeVault = object : GoogleAccount.Vault {
        override fun seal(plain: ByteArray) = plain.reversedArray()
        override fun open(sealed: ByteArray) = sealed.reversedArray()
    }

    private fun idToken(email: String) = "h." + b64("""{"email":"$email","sub":"1"}""") + ".s"

    @Test
    fun theConnectionIsKeptSealedAndReadBack() {
        val f = tmp.root.resolve("google.bin")
        val acct = GoogleAccount(f, fakeVault)
        assertFalse(acct.connected)
        acct.save("""{"access_token":"at","expires_in":3599,"refresh_token":"rt-secret","scope":"openid ${GoogleAccount.CALENDAR}","id_token":"${idToken("me@gmail.com")}"}""", now = 0)
        assertFalse(String(f.readBytes()).contains("rt-secret"))     // never on disk in the clear
        val again = GoogleAccount(f, fakeVault)
        assertTrue(again.connected)
        assertEquals("me@gmail.com", again.email)
        assertTrue(again.has(GoogleAccount.CALENDAR))
        assertFalse(again.has(GoogleAccount.GMAIL_COMPOSE))
    }

    @Test
    fun withoutARefreshTokenNothingIsKept() {
        val f = tmp.root.resolve("google.bin")
        try {
            GoogleAccount(f, fakeVault).save("""{"access_token":"at"}""", now = 0); fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("allow all the boxes"))
        }
        assertFalse(f.exists())
        assertNull(GoogleAccount.emailFromIdToken("garbage"))
    }

    @Test
    fun connectingAsksForOfflineAccessToExactlyTheseScopes() {
        val url = GoogleSignIn.authUrl("cid", "http://127.0.0.1:1", "CH", "st", GoogleAccount.SCOPES.joinToString(" "), offline = true)
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent%20select_account"))
        assertTrue(url.contains(java.net.URLEncoder.encode(GoogleAccount.GMAIL_COMPOSE, "UTF-8")))
        // Plain sign-in is unchanged: no offline access, no mail scopes.
        val plain = GoogleSignIn.authUrl("cid", "http://127.0.0.1:1", "CH", "st")
        assertFalse(plain.contains("access_type"))
        assertFalse(plain.contains("gmail"))
    }
}
