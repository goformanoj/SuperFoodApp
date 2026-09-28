package com.jarvis.os.desktop.agent

import com.jarvis.os.data.ChatTurn
import com.jarvis.os.desktop.brain.Brain
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AgentLoopTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.parse("2026-09-28T14:00").atZone(zone).toInstant().toEpochMilli()
    private val brain = Brain.inMemory { now }
    private val host = object : ToolBox.Host {
        override fun openUrl(url: String) = true
        override fun openApp(name: String): String? = null
        override fun clipboardText(): String? = null
    }
    private val tools = ToolBox(brain, host, { now }, zone)
    @After fun close() = brain.close()

    private fun call(id: String, name: String, args: String) = AgentClient.ToolCall(id, name, args)

    /** A scripted model: returns the replies in order and records what it was sent. */
    private class Script(vararg replies: AgentClient.Reply) {
        val queue = ArrayDeque(replies.toList())
        val sent = mutableListOf<JSONArray>()
        suspend fun step(m: JSONArray, c: String, t: JSONArray): AgentClient.Reply {
            sent += JSONArray(m.toString()); return queue.removeFirst()
        }
    }

    @Test
    fun remindAtFive_S1() = runBlocking {
        val script = Script(
            AgentClient.Reply("", listOf(call("c1", "set_reminder", """{"text":"Call the bank","at":"2026-09-28T17:00"}"""))),
            AgentClient.Reply("Done — I'll remind you at 5 to call the bank.", emptyList()),
        )
        val steps = mutableListOf<ToolBox.Result>()
        val loop = AgentLoop(tools, script::step, approve = { error("no approval needed") }, onStep = { _, r -> steps += r })
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "Remind me at 5 to call the bank")), "ctx", "conv")
        assertEquals("Done — I'll remind you at 5 to call the bank.", answer)
        assertEquals(1, brain.upcomingReminders().size)
        assertTrue(steps.single().ok)
        // The second model call saw the tool call AND its result.
        val second = script.sent[1]
        assertEquals("assistant", second.getJSONObject(1).getString("role"))
        assertEquals("tool", second.getJSONObject(2).getString("role"))
        assertEquals("c1", second.getJSONObject(2).getString("tool_call_id"))
    }

    @Test
    fun irreversibleStepsWaitForApprovalAndCanBeDeclined() = runBlocking {
        brain.addTask("Old task")
        val script = Script(
            AgentClient.Reply("", listOf(call("d", "delete_task", """{"task":"Old task"}"""))),
            AgentClient.Reply("Okay, I left it.", emptyList()),
        )
        val asked = mutableListOf<String>()
        val loop = AgentLoop(tools, script::step, approve = { asked += it; false }, onStep = { _, _ -> })
        loop.run(listOf(ChatTurn(ChatTurn.USER, "delete old task")), "", null)
        assertEquals(listOf("Delete the task matching “Old task”"), asked)
        assertEquals(1, brain.openTasks().size)     // NOT deleted
        assertTrue(script.sent[1].getJSONObject(2).getString("content").contains("declined"))
    }

    @Test
    fun approvedIrreversibleStepsRun() = runBlocking {
        brain.addTask("Old task")
        val script = Script(
            AgentClient.Reply("", listOf(call("d", "delete_task", """{"task":"Old task"}"""))),
            AgentClient.Reply("Deleted.", emptyList()),
        )
        AgentLoop(tools, script::step, approve = { true }, onStep = { _, _ -> }).run(listOf(ChatTurn(ChatTurn.USER, "x")), "", null)
        assertTrue(brain.openTasks().isEmpty())
    }

    @Test
    fun undoableStepsNeverAskForApproval() = runBlocking {
        val script = Script(
            AgentClient.Reply("", listOf(call("a", "add_task", """{"title":"Buy milk"}"""), call("b", "remember", """{"fact":"Likes oat milk"}"""))),
            AgentClient.Reply("Added and noted.", emptyList()),
        )
        var asked = false
        AgentLoop(tools, script::step, approve = { asked = true; true }, onStep = { _, _ -> }).run(listOf(ChatTurn(ChatTurn.USER, "x")), "", "c")
        assertFalse(asked)
        assertEquals("Buy milk", brain.openTasks().single().title)
        assertEquals("Likes oat milk", brain.memories().single().text)
    }

    @Test
    fun theStepBudgetStopsARunawayModel() = runBlocking {
        val forever = AgentClient.Reply("", listOf(call("x", "list_tasks", """{"which":"open"}""")))
        var calls = 0
        val loop = AgentLoop(tools, { _, _, _ -> calls++; forever }, approve = { true }, onStep = { _, _ -> }, maxSteps = 3)
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "x")), "", null)
        assertEquals(3, calls)
        assertTrue(answer.startsWith("I stopped there"))
    }

    @Test
    fun unknownToolsAreReportedNotRun() = runBlocking {
        val script = Script(
            AgentClient.Reply("", listOf(call("z", "format_disk", "{}"))),
            AgentClient.Reply("I can't do that.", emptyList()),
        )
        val steps = mutableListOf<ToolBox.Result>()
        AgentLoop(tools, script::step, approve = { true }, onStep = { _, r -> steps += r }).run(listOf(ChatTurn(ChatTurn.USER, "x")), "", null)
        assertFalse(steps.single().ok)
    }

    @Test
    fun actionCardsRoundTrip() {
        val c = ActionCard("Added task “x”", true, ToolBox.Undo("task", "id1"))
        assertEquals(c, ActionCard.decode(c.encode()))
        assertEquals(c.copy(undone = true), ActionCard.decode(c.copy(undone = true).encode()))
        assertEquals(null, ActionCard.decode("not json"))
    }

    @Test
    fun agentClientParsesToolCallsAndPlainReplies() {
        val r = AgentClient.parse("""{"reply":"","tool_calls":[{"id":"1","name":"add_task","arguments":"{\"title\":\"x\"}"}],"plan":"free"}""")
        assertEquals(listOf(AgentClient.ToolCall("1", "add_task", """{"title":"x"}""")), r.toolCalls)
        assertEquals("Hi.", AgentClient.parse("""{"reply":" Hi. "}""").text)
        assertTrue(AgentClient.parse("""{"reply":"x"}""").toolCalls.isEmpty())
    }

    @Test
    fun shortcutMatching() {
        val names = listOf("Google Chrome", "Microsoft Word", "Word Pad Helper", "Notepad++", "Calculator")
        assertEquals("Google Chrome", ShortcutMatch.best("chrome", names))
        assertEquals("Microsoft Word", ShortcutMatch.best("microsoft word", names))
        assertEquals("Notepad++", ShortcutMatch.best("notepad", names))
        assertEquals(null, ShortcutMatch.best("photoshop", names))
    }
}
