package com.jarvis.os.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent-loop prompt is terse on purpose (it rides on every step), so the
 * rules earned from device failures must not be dropped by a careless edit.
 */
class AgentPromptTest {

    @Test
    fun `every action marker is documented`() {
        listOf("<<TAP|", "<<TYPE|", "<<ENTER>>", "<<PICK|", "<<OPEN|", "<<BACK>>", "<<HOME>>", "<<DONE>>", "<<ASK|")
            .forEach { assertTrue("missing $it", AGENT_PROMPT.contains(it)) }
    }

    @Test
    fun `the stay-in-the-app rule survives`() {
        // Earned from the trace where "play a song" wandered into the Phone app.
        assertTrue(AGENT_PROMPT.contains("Stay in the app"))
    }

    @Test
    fun `the never-spend rule survives`() {
        val p = AGENT_PROMPT.lowercase()
        assertTrue(p.contains("sends") || p.contains("buys") || p.contains("pays"))
    }

    @Test
    fun `the do-not-back-out-of-a-just-opened-app rule survives`() {
        // Earned from the trace where "opened Facebook" was immediately followed by
        // <<BACK>>, backing straight out of the app the errand needed.
        val p = AGENT_PROMPT.lowercase()
        assertTrue(p.contains("just opened") && p.contains("back"))
    }

    @Test
    fun `the type-only-the-query rule survives`() {
        // Earned from the trace where the user's conversational sentence became the
        // goal and got typed verbatim into a search box.
        assertTrue(AGENT_PROMPT.lowercase().contains("never the whole goal"))
    }

    @Test
    fun `agentStep lays out the goal, history and screen`() {
        val q = agentStep(goal = "play music", done = "opened Amazon Music", screen = "[Play] [Shuffle]")
        assertTrue(q.contains("GOAL: play music"))
        assertTrue(q.contains("DONE: opened Amazon Music"))
        assertTrue(q.contains("ON SCREEN: [Play] [Shuffle]"))
    }

    @Test
    fun `agentStep admits when the screen is unreadable`() {
        assertTrue(agentStep(goal = "g", done = "d", screen = "").contains("cannot read the screen"))
    }
}
