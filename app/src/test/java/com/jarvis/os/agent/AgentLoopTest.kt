package com.jarvis.os.agent

import com.jarvis.os.data.Brain
import com.jarvis.os.data.ChatTurn
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentLoopTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.parse("2026-09-28T14:00").atZone(zone).toInstant().toEpochMilli()
    private val brain = Brain.inMemory { now }
    private val tools = ToolBox(brain, { now }, zone)
    @After fun close() = brain.close()

    private fun call(id: String, name: String, args: String) = AgentClient.ToolCall(id, name, args)

    /** A scripted model: returns the replies in order and records what it was sent. */
    private class Script(vararg replies: AgentClient.Reply) {
        val queue = ArrayDeque(replies.toList())
        val sent = mutableListOf<JSONArray>()
        val toolsSent = mutableListOf<JSONArray>()
        suspend fun step(m: JSONArray, t: JSONArray): AgentClient.Reply {
            sent += JSONArray(m.toString()); toolsSent += t; return queue.removeFirst()
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
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "Remind me at 5 to call the bank")))
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
        val loop = AgentLoop(tools, script::step, approve = { asked += it.description; false }, onStep = { _, _ -> })
        loop.run(listOf(ChatTurn(ChatTurn.USER, "delete old task")))
        assertEquals(listOf("Delete the task matching “Old task”"), asked)
        assertEquals(1, brain.openTasks().size) // NOT deleted
        assertTrue(script.sent[1].getJSONObject(2).getString("content").contains("declined"))
    }

    @Test
    fun approvedIrreversibleStepsRun() = runBlocking {
        brain.addTask("Old task")
        val script = Script(
            AgentClient.Reply("", listOf(call("d", "delete_task", """{"task":"Old task"}"""))),
            AgentClient.Reply("Deleted.", emptyList()),
        )
        AgentLoop(tools, script::step, approve = { true }, onStep = { _, _ -> }).run(listOf(ChatTurn(ChatTurn.USER, "x")))
        assertTrue(brain.openTasks().isEmpty())
    }

    @Test
    fun undoableStepsNeverAskForApproval() = runBlocking {
        val script = Script(
            AgentClient.Reply("", listOf(call("a", "add_task", """{"title":"Buy milk"}"""), call("b", "remember", """{"fact":"Likes oat milk"}"""))),
            AgentClient.Reply("Added and noted.", emptyList()),
        )
        var asked = false
        AgentLoop(tools, script::step, approve = { asked = true; true }, onStep = { _, _ -> }).run(listOf(ChatTurn(ChatTurn.USER, "x")))
        assertFalse(asked)
        assertEquals("Buy milk", brain.openTasks().single().title)
        assertEquals("Likes oat milk", brain.memories().single().text)
    }

    @Test
    fun theStepBudgetStopsARunawayModelThatMadeNoRealProgress() = runBlocking {
        // An empty brain, so "complete_task" genuinely fails every time — no step here ever
        // succeeds, so the fallback has nothing to report and stays the plain apology.
        val forever = AgentClient.Reply("", listOf(call("x", "complete_task", """{"task":"ghost"}""")))
        var calls = 0
        val loop = AgentLoop(tools, { _, _ -> calls++; forever }, approve = { true }, onStep = { _, _ -> }, maxSteps = 3)
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "x")))
        assertEquals(3, calls)
        assertTrue(answer.startsWith("I stopped there"))
    }

    @Test
    fun theLastStepOffersNoToolsAtAllAHardGuaranteeNotJustANudge() = runBlocking {
        // Ported from the laptop's own fix (2026-10-01, found live via its eval harness):
        // softer "wrap up" nudges measurably help but a persistent model can ignore them.
        // The LAST step removes the option entirely — this script's second reply tries a
        // tool anyway (a stubborn model), and it must never actually run.
        val script = Script(
            AgentClient.Reply("", listOf(call("a", "add_task", """{"title":"Buy milk"}"""))),
            AgentClient.Reply("", listOf(call("b", "add_task", """{"title":"Should never run"}"""))),
        )
        val loop = AgentLoop(tools, script::step, approve = { true }, onStep = { _, _ -> }, maxSteps = 2)
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "x")))
        assertTrue("the first (non-last) step should still get the real tool schemas", script.toolsSent[0].length() > 0)
        assertEquals("the LAST step must get NO tools at all", 0, script.toolsSent[1].length())
        assertEquals(listOf("Buy milk"), brain.openTasks().map { it.title })   // the second call never actually ran
        assertTrue(answer, answer.startsWith("I ran out of steps before finishing"))
        assertTrue(answer, answer.contains("Buy milk"))   // real progress is reported, not just an apology
    }

    @Test
    fun theLastStepStillAnswersNormallyWhenTheModelJustAnswers() = runBlocking {
        val script = Script(
            AgentClient.Reply("", listOf(call("a", "list_tasks", """{"which":"open"}"""))),
            AgentClient.Reply("All done, nothing pending.", emptyList()),
        )
        val loop = AgentLoop(tools, script::step, approve = { true }, onStep = { _, _ -> }, maxSteps = 2)
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "x")))
        assertEquals(0, script.toolsSent[1].length())
        assertEquals("All done, nothing pending.", answer)
    }

    @Test
    fun unknownToolsAreReportedNotRun() = runBlocking {
        val script = Script(
            AgentClient.Reply("", listOf(call("z", "format_disk", "{}"))),
            AgentClient.Reply("I can't do that.", emptyList()),
        )
        val steps = mutableListOf<ToolBox.Result>()
        AgentLoop(tools, script::step, approve = { true }, onStep = { _, r -> steps += r }).run(listOf(ChatTurn(ChatTurn.USER, "x")))
        assertFalse(steps.single().ok)
    }

    @Test
    fun agentClientParsesToolCallsAndPlainReplies() {
        val r = AgentClient.parse("""{"reply":"","tool_calls":[{"id":"1","name":"add_task","arguments":"{\"title\":\"x\"}"}],"plan":"free"}""")
        assertEquals(listOf(AgentClient.ToolCall("1", "add_task", """{"title":"x"}""")), r.toolCalls)
        assertEquals("Hi.", AgentClient.parse("""{"reply":" Hi. "}""").text)
        assertTrue(AgentClient.parse("""{"reply":"x"}""").toolCalls.isEmpty())
    }
}
