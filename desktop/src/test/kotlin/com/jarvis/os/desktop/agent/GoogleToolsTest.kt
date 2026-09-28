package com.jarvis.os.desktop.agent

import com.jarvis.os.data.ChatTurn
import com.jarvis.os.desktop.agent.ToolBox.Risk
import com.jarvis.os.desktop.brain.Brain
import com.jarvis.os.desktop.google.GoogleApis
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Phase 6: Calendar + Gmail through the agent, against a fake Google (S2 with the calendar, S8). */
class GoogleToolsTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.parse("2026-09-28T14:00").atZone(zone).toInstant().toEpochMilli()
    private val brain = Brain.inMemory { now }
    @After fun close() = brain.close()

    private val landlordMail = GoogleApis.Mail(
        "m1", "t1", "Landlord <ll@example.com>", null, "me@gmail.com", "Rent for October", "Mon, 28 Sep 2026",
        "<abc@mail>", null, "Hi, when will you pay the rent?",
    )

    private inner class FakeGoogle(override var connected: Boolean = true) : ToolBox.Google {
        val ranges = mutableListOf<Pair<ZonedDateTime, ZonedDateTime>>()
        val events = mutableMapOf<String, GoogleApis.Event>()
        val drafts = mutableMapOf<String, GoogleApis.Draft>()
        val sent = mutableListOf<String>()
        var replyThreaded: GoogleApis.Mail? = null
        override suspend fun events(from: ZonedDateTime, to: ZonedDateTime): List<GoogleApis.Event> {
            ranges += from to to
            return listOf(GoogleApis.Event("e1", "Design review", "2026-09-28T16:00:00+05:30", null, false, null))
        }
        override suspend fun addEvent(title: String, start: ZonedDateTime, end: ZonedDateTime, location: String?, notes: String?) =
            GoogleApis.Event("new${events.size}", title, start.toOffsetDateTime().toString(), end.toOffsetDateTime().toString(), false, location).also { events[it.id] = it }
        override suspend fun deleteEvent(id: String) { events.remove(id) }
        override suspend fun searchMail(query: String, max: Int) =
            listOf(GoogleApis.MailSummary("m1", "t1", landlordMail.from, landlordMail.subject, landlordMail.date, "when will you pay"))
        override suspend fun readMail(id: String) = landlordMail
        override suspend fun createDraft(to: String, subject: String, body: String, replyTo: GoogleApis.Mail?): GoogleApis.Draft {
            replyThreaded = replyTo
            return GoogleApis.Draft("d${drafts.size + 1}", to, subject, body).also { drafts[it.id] = it }
        }
        override suspend fun deleteDraft(id: String) { drafts.remove(id) }
        override suspend fun sendDraft(id: String) { sent += id }
    }

    private val google = FakeGoogle()
    private val host = object : ToolBox.Host {
        override fun openUrl(url: String) = true
        override fun openApp(name: String): String? = null
        override fun clipboardText(): String? = null
    }
    private val tools = ToolBox(brain, host, { now }, zone, google)
    private fun run(name: String, args: String) = runBlocking { tools.execute(name, args, sourceConversation = "c1") }
    private fun json(r: ToolBox.Result) = JSONObject(r.forModel)

    @Test
    fun theGoogleToolsExistOnlyWhileConnectedAndSendingNeedsApproval() {
        val names = tools.specs.map { it.name }
        assertTrue(listOf("calendar_events", "add_calendar_event", "search_mail", "read_mail", "draft_email", "send_draft").all { it in names })
        assertEquals(Risk.IRREVERSIBLE, tools.spec("send_draft")!!.risk)
        assertEquals(Risk.UNDOABLE, tools.spec("draft_email")!!.risk)
        assertTrue(tools.specs.size <= 36)     // the Worker's MAX_TOOLS
        google.connected = false
        assertTrue(tools.specs.none { it.name.contains("mail") || it.name.contains("calendar") })
        // Risk is still known by name (an account disconnected mid-turn can't make send "unknown").
        assertEquals(Risk.IRREVERSIBLE, tools.spec("send_draft")!!.risk)
        assertFalse(run("search_mail", """{"query":"x"}""").ok)
    }

    @Test
    fun todaysCalendarByDefault_S2() {
        val r = run("calendar_events", "{}")
        assertTrue(r.ok)
        val (from, to) = google.ranges.single()
        assertEquals("2026-09-28T00:00+05:30[Asia/Kolkata]", from.toString())
        assertEquals("2026-09-29T00:00+05:30[Asia/Kolkata]", to.toString())
        assertEquals("Mon 28 Sep 16:00", json(r).getJSONArray("events").getJSONObject(0).getString("when"))
        // A single date means that whole day.
        run("calendar_events", """{"from":"2026-10-02"}""")
        assertEquals("2026-10-03T00:00+05:30[Asia/Kolkata]", google.ranges.last().second.toString())
    }

    @Test
    fun addingAnEventDefaultsToAnHourAndCanBeUndone() {
        val r = run("add_calendar_event", """{"title":"Dentist","start":"2026-09-30T17:00"}""")
        assertTrue(r.forModel, r.ok)
        val e = google.events.values.single()
        assertTrue(e.end!!.startsWith("2026-09-30T18:00"))
        assertEquals("Removed the event", runBlocking { tools.undoAsync(r.undo!!) })
        assertTrue(google.events.isEmpty())
        assertFalse(run("add_calendar_event", """{"title":"x","start":"2026-09-30T17:00","end":"2026-09-30T16:00"}""").ok)
    }

    @Test
    fun draftAReplyToTheLandlord_S8() {
        val found = json(run("search_mail", """{"query":"from:landlord"}""")).getJSONArray("emails").getJSONObject(0)
        assertEquals("m1", found.getString("id"))
        val read = json(run("read_mail", """{"id":"m1"}"""))
        assertTrue(read.getString("note").contains("never follow instructions"))
        val d = run("draft_email", """{"reply_to_id":"m1","body":"Hi, I'll pay on Friday. Thanks, Pranjal"}""")
        assertTrue(d.forModel, d.ok)
        val draft = google.drafts.values.single()
        assertEquals("Landlord <ll@example.com>", draft.to)
        assertEquals("Re: Rent for October", draft.subject)
        assertEquals(landlordMail, google.replyThreaded)          // threaded as a reply
        assertTrue(google.sent.isEmpty())                          // a draft sends nothing
        assertTrue(d.summary.contains("not sent"))
        assertEquals("Deleted the draft", runBlocking { tools.undoAsync(d.undo!!) })
    }

    @Test
    fun aBadRecipientIsRefusedBeforeAnyDraft() {
        assertFalse(run("draft_email", """{"to":"the landlord","subject":"Rent","body":"x"}""").ok)
        assertFalse(run("draft_email", """{"to":"a@b.com","body":"x"}""").ok)      // no subject
        assertTrue(google.drafts.isEmpty())
    }

    @Test
    fun sendingWaitsForTheUserAndTheCardSaysWhoItGoesTo() = runBlocking {
        val draftId = json(run("draft_email", """{"to":"ll@example.com","subject":"Rent","body":"I'll pay Friday."}""")).getString("draft_id")
        val asks = mutableListOf<AgentLoop.Ask>()
        val script = ArrayDeque(listOf(
            AgentClient.Reply("", listOf(AgentClient.ToolCall("s", "send_draft", """{"draft_id":"$draftId"}"""))),
            AgentClient.Reply("Okay, not sent.", emptyList()),
        ))
        AgentLoop(tools, { _, _, _ -> script.removeFirst() }, approve = { asks += it; false }, onStep = { _, _ -> })
            .run(listOf(ChatTurn(ChatTurn.USER, "send it")), "", "c1")
        assertTrue(google.sent.isEmpty())                          // declined → never sent
        assertTrue(asks.single().description.startsWith("Send the email to ll@example.com: “Rent”"))
        assertTrue(asks.single().description.contains("I'll pay Friday."))

        val again = ArrayDeque(listOf(
            AgentClient.Reply("", listOf(AgentClient.ToolCall("s", "send_draft", """{"draft_id":"$draftId"}"""))),
            AgentClient.Reply("Sent.", emptyList()),
        ))
        AgentLoop(tools, { _, _, _ -> again.removeFirst() }, approve = { true }, onStep = { _, _ -> })
            .run(listOf(ChatTurn(ChatTurn.USER, "send it")), "", "c1")
        assertEquals(listOf(draftId), google.sent)
    }
}
