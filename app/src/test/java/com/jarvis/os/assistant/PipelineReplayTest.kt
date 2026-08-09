package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenActions
import com.jarvis.os.control.ScreenStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trace-replay regression tests: real failures seen on a device, frozen as
 * fixtures so they can never come back silently.
 *
 * Each case is `(what the user said, what the model replied)` run through the
 * exact same guard chain the engine applies to screen actions in
 * [com.jarvis.os.assistant.AssistantEngine.ask] — parse, then AskGuard, then
 * SendGuard, then SpendGuard — and asserts the safe plan that survives. When a
 * new device trace turns up a bad plan, add it here first, watch it fail, then
 * fix the guard.
 */
class PipelineReplayTest {

    /** Mirrors the screen-action guard order in AssistantEngine.ask(). */
    private fun safeSteps(userText: String, modelReply: String): List<ScreenStep> {
        val parsed = ScreenActions.parse(modelReply)
        val asked = AskGuard.apply(parsed.clean, parsed.steps)
        val guarded = SendGuard.apply(userText, asked)
        return SpendGuard.apply(userText, guarded)
    }

    @Test
    fun `asking and acting at once drops every action`() {
        // Device trace: the reply asked which app to use AND opened YouTube anyway.
        val steps = safeSteps(
            userText = "play some music",
            modelReply = "I'll help with that. Which music app would you like me to use?\n<<OPEN|YouTube>>",
        )
        assertEquals(emptyList<ScreenStep>(), steps)
    }

    @Test
    fun `type without send drops the trailing submit`() {
        // "type hello in the chat" must not send it.
        val steps = safeSteps(
            userText = "type hello in the chat",
            modelReply = "<<TAP|Message>> <<TYPE|hello>> <<ENTER>>",
        )
        assertEquals(listOf(ScreenStep.Tap("Message"), ScreenStep.Type("hello")), steps)
        assertFalse(steps.contains(ScreenStep.Enter))
    }

    @Test
    fun `nobody asked to check out, so checkout is withheld`() {
        // Device trace: "add some bread" produced a plan ending in Tap(Checkout).
        val steps = safeSteps(
            userText = "add some bread on blinkit",
            modelReply = "<<OPEN|Blinkit>> <<TAP|Search>> <<TYPE|bread>> <<ENTER>> <<TAP|Add>> <<TAP|Checkout>>",
        )
        assertFalse("checkout must be withheld", steps.contains(ScreenStep.Tap("Checkout")))
        assertTrue("the recoverable part still runs", steps.contains(ScreenStep.Tap("Add")))
    }

    @Test
    fun `a plain in-app command passes through unchanged`() {
        val steps = safeSteps(
            userText = "open whatsapp and tap mom",
            modelReply = "<<OPEN|WhatsApp>> <<TAP|Mom>>",
        )
        assertEquals(listOf(ScreenStep.Open("WhatsApp"), ScreenStep.Tap("Mom")), steps)
    }

    @Test
    fun `the errand app-lock blocks wandering into an unrelated app`() {
        // Device trace: "play a song" (misheard "…call Khat") tried to open Phone.
        // The app-lock lives in AgentLoop; frozen here as part of the same story.
        val move = AgentLoop.parseMove("<<OPEN|Phone>>", stayInApp = "Amazon Music")
        assertTrue(move is AgentMove.Blocked)
        assertEquals(AgentLoop.LEFT_APP, (move as AgentMove.Blocked).reason)
    }
}
