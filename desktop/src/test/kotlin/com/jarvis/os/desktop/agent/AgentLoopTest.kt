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
        val toolsSent = mutableListOf<JSONArray>()
        suspend fun step(m: JSONArray, c: String, t: JSONArray): AgentClient.Reply {
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
        val loop = AgentLoop(tools, script::step, approve = { asked += it.description; false }, onStep = { _, _ -> })
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
    fun theStepBudgetStopsARunawayModelThatMadeNoRealProgress() = runBlocking {
        // An empty brain, so "complete_task" genuinely fails every time — no step here ever
        // succeeds, so the fallback has nothing to report and stays the plain apology.
        val forever = AgentClient.Reply("", listOf(call("x", "complete_task", """{"task":"ghost"}""")))
        var calls = 0
        val loop = AgentLoop(tools, { _, _, _ -> calls++; forever }, approve = { true }, onStep = { _, _ -> }, maxSteps = 3)
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "x")), "", null)
        assertEquals(3, calls)
        assertTrue(answer.startsWith("I stopped there"))
    }

    @Test
    fun theLastStepOffersNoToolsAtAllAHardGuaranteeNotJustANudge() = runBlocking {
        // Found live (2026-10-01 eval): the softer "wrap up" nudges above measurably help but
        // a sufficiently persistent model can still ignore them (a web_search loop ran 6
        // times before stalling). The LAST step removes the option entirely: no tools are
        // sent, so there is nothing left to call — this script's second reply tries one
        // anyway (a stubborn model), and it must never actually run.
        val script = Script(
            AgentClient.Reply("", listOf(call("a", "add_task", """{"title":"Buy milk"}"""))),
            AgentClient.Reply("", listOf(call("b", "add_task", """{"title":"Should never run"}"""))),
        )
        val loop = AgentLoop(tools, script::step, approve = { true }, onStep = { _, _ -> }, maxSteps = 2)
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "x")), "", null)
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
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "x")), "", null)
        assertEquals(0, script.toolsSent[1].length())           // still offered no tools...
        assertEquals("All done, nothing pending.", answer)      // ...but a normal text answer works exactly as before
    }

    @Test
    fun repeatingTheExactSameFailingCallIsShortCircuitedNotRerun() = runBlocking {
        // Found live (2026-10-01 eval): a failed open_file got retried identically, burning
        // the whole step budget before stalling. The second identical attempt must never
        // actually touch the filesystem again — it's caught in code before that.
        val badPath = """{"path":"C:\\nonexistent\\ghost.pdf"}"""
        val script = Script(
            AgentClient.Reply("", listOf(call("a", "open_file", badPath))),
            AgentClient.Reply("", listOf(call("b", "open_file", badPath))),   // retries the identical call
            AgentClient.Reply("I couldn't open that file — it doesn't seem to exist.", emptyList()),
        )
        val steps = mutableListOf<ToolBox.Result>()
        val loop = AgentLoop(tools, script::step, approve = { true }, onStep = { _, r -> steps += r })
        val answer = loop.run(listOf(ChatTurn(ChatTurn.USER, "open ghost.pdf")), "", null)
        assertEquals(2, steps.size)
        assertFalse(steps[0].ok)
        assertFalse(steps[1].ok)
        // The first call genuinely ran (ToolBox's own "no such file" message); the second
        // was intercepted before ToolBox ran at all — a different, code-level message.
        assertTrue(steps[0].summary, steps[0].summary.contains("no file"))
        assertTrue(steps[1].summary, steps[1].summary.contains("Skipped repeating"))
        assertTrue(script.sent[2].getJSONObject(4).getString("content").contains("already failed earlier in this turn"))
        assertFalse("the model should have stopped retrying and answered instead of stalling", answer.startsWith("I stopped there"))
    }

    @Test
    fun aDifferentFailingCallIsNotTreatedAsARepeat() = runBlocking {
        // Different arguments (or a different tool) must run for real even after an
        // unrelated failure — the guard is about repeating the SAME call, not "a failure happened".
        val script = Script(
            AgentClient.Reply("", listOf(call("a", "open_file", """{"path":"C:\\nonexistent\\one.pdf"}"""))),
            AgentClient.Reply("", listOf(call("b", "open_file", """{"path":"C:\\nonexistent\\two.pdf"}"""))),
            AgentClient.Reply("Neither file exists.", emptyList()),
        )
        val steps = mutableListOf<ToolBox.Result>()
        AgentLoop(tools, script::step, approve = { true }, onStep = { _, r -> steps += r }).run(listOf(ChatTurn(ChatTurn.USER, "x")), "", null)
        assertEquals(2, steps.size)
        steps.forEach { assertTrue(it.summary, it.summary.contains("no file")) }   // both ran for real
    }

    @Test
    fun severalGenuinelyDifferentFailuresGetToldToWrapUp() = runBlocking {
        // Found live (2026-10-01 eval): after one failure the model kept trying DIFFERENT
        // variations (never repeating one identical call, so the guard above never caught
        // it) until it ran out of steps. These two calls are different tasks, so the
        // exact-repeat guard must NOT be what fires here — the nudge is about accumulated
        // failures, not repetition.
        val script = Script(
            AgentClient.Reply("", listOf(call("a", "complete_task", """{"task":"ghost one"}"""))),
            AgentClient.Reply("", listOf(call("b", "complete_task", """{"task":"ghost two"}"""))),
            AgentClient.Reply("Neither task exists.", emptyList()),
        )
        val steps = mutableListOf<ToolBox.Result>()
        AgentLoop(tools, script::step, approve = { true }, onStep = { _, r -> steps += r })
            .run(listOf(ChatTurn(ChatTurn.USER, "x")), "", null)
        assertEquals(2, steps.size)
        assertFalse(steps[0].ok); assertFalse(steps[1].ok)
        // First failure: no nudge yet (only one failure so far).
        assertFalse(script.sent[1].getJSONObject(2).getString("content").contains("Several actions have failed"))
        // Second failure crosses the threshold: the MODEL-FACING message carries the nudge...
        assertTrue(script.sent[2].getJSONObject(4).getString("content").contains("Several actions have failed"))
        // ...but the step card shown to the user is untouched (still just the real reason it failed).
        assertFalse(steps[1].forModel.contains("Several actions have failed"))
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
    fun agentClientParsesWhichBackendAndModelAnswered() {
        // Tracked so a quality problem in the TEXT (e.g. degenerate output) can be traced to
        // a specific platform in the router's failover chain, not blamed on "the model" vaguely.
        val r = AgentClient.parse("""{"reply":"hi","model":"llama-3.3-70b","backend":"groq"}""")
        assertEquals("groq", r.backend)
        assertEquals("llama-3.3-70b", r.model)
        // Absent entirely (e.g. a non-agent path, or an older server) — never crashes, just null.
        val none = AgentClient.parse("""{"reply":"hi"}""")
        assertEquals(null, none.backend)
        assertEquals(null, none.model)
    }

    @Test
    fun onReplyFiresForEveryStepWithTheRawReply() = runBlocking {
        val script = Script(
            AgentClient.Reply("", listOf(call("a", "add_task", """{"title":"Buy milk"}""")), backend = "cloudflare", model = "gpt-oss-120b"),
            AgentClient.Reply("Added.", emptyList(), backend = "groq", model = "llama-3.3-70b"),
        )
        val seen = mutableListOf<AgentClient.Reply>()
        AgentLoop(tools, script::step, approve = { true }, onStep = { _, _ -> }, onReply = { seen += it })
            .run(listOf(ChatTurn(ChatTurn.USER, "x")), "", null)
        assertEquals(listOf("cloudflare", "groq"), seen.map { it.backend })
        assertEquals(listOf("gpt-oss-120b", "llama-3.3-70b"), seen.map { it.model })
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
