package com.jarvis.os.desktop.agent

import com.jarvis.os.desktop.agent.ToolBox.Risk
import com.jarvis.os.desktop.brain.Brain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ToolBoxTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-09-28T14:00")
    private val brain = Brain.inMemory { now }

    private val opened = mutableListOf<String>()
    private var clip: String? = "copied text"
    private val host = object : ToolBox.Host {
        override fun openUrl(url: String): Boolean { opened += url; return true }
        override fun openApp(name: String): String? = if (name.equals("notepad", true)) "Notepad".also { opened += it } else null
        override fun clipboardText(): String? = clip
    }
    private val tools = ToolBox(brain, host, clock = { now }, zone = zone)
    @After fun close() = brain.close()

    private fun run(name: String, args: String) = kotlinx.coroutines.runBlocking { tools.execute(name, args, sourceConversation = "conv-1") }
    private fun json(r: ToolBox.Result) = JSONObject(r.forModel)

    // ── the permission model is in code ──

    @Test
    fun destructiveToolsAreIrreversibleAndEverythingElseIsNot() {
        val irreversible = tools.specs.filter { it.risk == Risk.IRREVERSIBLE }.map { it.name }.toSet()
        assertEquals(setOf("delete_task", "forget"), irreversible)
        // Every undoable tool really produces an Undo when it succeeds.
        assertNotNull(run("add_task", """{"title":"x"}""").undo)
        assertNotNull(run("set_reminder", """{"text":"y","at":"2026-09-29T09:00"}""").undo)
        assertNotNull(run("save_note", """{"title":"n","body":"b"}""").undo)
        assertNotNull(run("remember", """{"fact":"Likes tea"}""").undo)
    }

    @Test
    fun schemasAreWellFormedFunctionTools() {
        val arr = tools.schemas()
        assertEquals(tools.specs.size, arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            assertEquals("function", f.getString("type"))
            assertTrue(f.getJSONObject("function").getString("name").matches(Regex("[a-z_]+")))
            assertEquals("object", f.getJSONObject("function").getJSONObject("parameters").getString("type"))
        }
    }

    // ── tasks ──

    @Test
    fun addTaskWithADueTimeIsSourcedAndUndoable() {
        val r = run("add_task", """{"title":"Call the bank","due":"2026-09-29T17:00"}""")
        assertTrue(r.ok)
        assertEquals("Added task “Call the bank” · Tomorrow 17:00", r.summary)
        val t = brain.openTasks().single()
        assertEquals(at("2026-09-29T17:00"), t.dueAt)
        assertEquals("conv-1", t.sourceConversation)
        tools.undo(r.undo!!)
        assertTrue(brain.openTasks().isEmpty())
    }

    @Test
    fun aBadDateIsReportedNotGuessed() {
        val r = run("add_task", """{"title":"x","due":"next blue moon"}""")
        assertFalse(r.ok)
        assertTrue(brain.openTasks().isEmpty())
    }

    @Test
    fun completeFindsByPartOfTheTitleAndCanBeUndone() {
        run("add_task", """{"title":"Send the deck to Priya"}""")
        val r = run("complete_task", """{"task":"deck"}""")
        assertTrue(r.ok)
        assertTrue(brain.openTasks().isEmpty())
        tools.undo(r.undo!!)
        assertEquals(1, brain.openTasks().size)
    }

    @Test
    fun completeMatchesAParaphraseNotJustAnExactSubstring() {
        // Found live (2026-10-01 eval): "sending the deck to Priya" doesn't literally
        // contain (or get contained by) "Send the deck to Priya" — a plain substring
        // check missed a real, existing task over one word's verb form.
        run("add_task", """{"title":"Send the deck to Priya"}""")
        val r = run("complete_task", """{"task":"sending the deck to priya"}""")
        assertTrue(r.forModel, r.ok)
        assertTrue(brain.openTasks().isEmpty())
    }

    @Test
    fun theParaphraseFallbackStaysConservative() {
        // A genuinely different task must NOT match just because it shares one word.
        run("add_task", """{"title":"Send the deck to Priya"}""")
        val r = run("complete_task", """{"task":"send an email to the landlord"}""")
        assertFalse(r.ok)
    }

    @Test
    fun ambiguousMatchesAskInsteadOfGuessing() {
        run("add_task", """{"title":"Call the bank"}""")
        run("add_task", """{"title":"Call mom"}""")
        val r = run("complete_task", """{"task":"call"}""")
        assertFalse(r.ok)
        assertTrue(json(r).getString("error").contains("Several tasks match"))
        assertEquals(2, brain.openTasks().size)
    }

    @Test
    fun listTasksToday() {
        run("add_task", """{"title":"Today thing","due":"2026-09-28T18:00"}""")
        run("add_task", """{"title":"Later thing","due":"2026-10-05T09:00"}""")
        assertEquals(1, json(run("list_tasks", """{"which":"today"}""")).getInt("count"))
        assertEquals(2, json(run("list_tasks", """{"which":"open"}""")).getInt("count"))
    }

    // ── reminders ──

    @Test
    fun reminderNeedsAFutureTime() {
        assertFalse(run("set_reminder", """{"text":"x","at":"2026-09-27T10:00"}""").ok)
        assertFalse(run("set_reminder", """{"text":"x","at":""}""").ok)
        val r = run("set_reminder", """{"text":"Call the bank","at":"2026-09-28 17:00"}""")
        assertTrue(r.ok)
        assertEquals(at("2026-09-28T17:00"), brain.upcomingReminders().single().at)
    }

    // ── memory ──

    @Test
    fun secretsAreNeverRemembered() {
        for (s in listOf("My ATM PIN is 4821", "OTP 482913", "card 4111 1111 1111 1111", "wifi password is hunter2")) {
            assertFalse(s, run("remember", JSONObject().put("fact", s).toString()).ok)
        }
        assertTrue(brain.memories().isEmpty())
        assertTrue(run("remember", """{"fact":"Sister is Asha","kind":"person"}""").ok)
        assertEquals(Brain.MemoryKind.PERSON, brain.memories().single().kind)
    }

    // ── search + host ──

    @Test
    fun searchReturnsPlainExcerpts() {
        run("save_note", """{"title":"Budget","body":"We agreed the trip budget is 40k"}""")
        val res = json(run("search_my_stuff", """{"query":"trip budget"}""")).getJSONArray("results")
        assertEquals(1, res.length())
        assertFalse(res.getJSONObject(0).getString("excerpt").contains("["))
    }

    @Test
    fun onlyWebUrlsOpen() {
        assertFalse(run("open_url", """{"url":"file:///C:/Windows/System32"}""").ok)
        assertTrue(run("open_url", """{"url":"https://example.com"}""").ok)
        assertEquals(listOf("https://example.com"), opened)
    }

    @Test
    fun unknownAppsAreReportedHonestly() {
        assertFalse(run("open_app", """{"name":"Photoshop"}""").ok)
        assertTrue(run("open_app", """{"name":"notepad"}""").ok)
    }

    @Test
    fun clipboard() {
        assertEquals("copied text", json(run("read_clipboard", "{}")).getString("clipboard"))
        clip = null
        assertFalse(run("read_clipboard", "{}").ok)
    }

    @Test
    fun garbageNeverThrows() {
        assertFalse(run("add_task", "{not json").ok)
        assertFalse(run("no_such_tool", "{}").ok)
    }

    @Test
    fun parseLocalAcceptsTheShapesModelsEmit() {
        assertEquals(at("2026-09-29T17:00"), tools.parseLocal("2026-09-29T17:00"))
        assertEquals(at("2026-09-29T17:00"), tools.parseLocal("2026-09-29 17:00"))
        assertEquals(at("2026-09-29T17:00"), tools.parseLocal("2026-09-29T17:00:00"))
        assertEquals(at("2026-09-29T09:00"), tools.parseLocal("2026-09-29"))
        assertNull(tools.parseLocal("tomorrow"))
    }
}
