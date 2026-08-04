package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop acts on a real phone without asking between steps, so what it
 * REFUSES to do matters more than what it manages. Every test here is either a
 * refusal or a limit.
 */
class AgentLoopTest {

    @Test
    fun `one action is taken from a reply, even when the model sends several`() {
        // The whole premise: anything after the first is planned against a screen
        // that does not exist yet, which is how the old executor went wrong.
        val move = AgentLoop.parseMove("<<TAP|Search>> <<TYPE|milk>> <<ENTER>>")
        assertEquals(AgentMove.Act(ScreenStep.Tap("Search")), move)
    }

    @Test
    fun `done is recognised`() {
        assertEquals(AgentMove.Done, AgentLoop.parseMove("<<DONE>>"))
        assertEquals(AgentMove.Done, AgentLoop.parseMove("That's everything. <<DONE>>"))
    }

    @Test
    fun `done alongside an action is not done`() {
        // A reply that says it has finished while still tapping has not finished.
        val move = AgentLoop.parseMove("<<TAP|Cart>> <<DONE>>")
        assertEquals(AgentMove.Act(ScreenStep.Tap("Cart")), move)
    }

    @Test
    fun `a tap that cannot be undone stops and asks`() {
        // The safety position. The loop is unattended between steps, so "shop
        // something for me" must not be able to place the order itself.
        listOf("Place order", "Buy now", "Pay", "Send", "Confirm", "Delete").forEach { label ->
            val move = AgentLoop.parseMove("<<TAP|$label>>")
            assertTrue("tapping '$label' must ask first, got $move", move is AgentMove.Ask)
            assertTrue((move as AgentMove.Ask).question.contains(label))
        }
    }

    @Test
    fun `ordinary taps do not ask`() {
        listOf("Search", "Cart", "Add", "Milk", "Sender name").forEach { label ->
            assertTrue(
                "'$label' is recoverable and should just run",
                AgentLoop.parseMove("<<TAP|$label>>") is AgentMove.Act,
            )
        }
    }

    @Test
    fun `typing and opening never ask, however alarming the text`() {
        // Only taps commit. Typing the word "pay" into a search box spends nothing.
        assertTrue(AgentLoop.parseMove("<<TYPE|pay rent>>") is AgentMove.Act)
        assertTrue(AgentLoop.parseMove("<<OPEN|Blinkit>>") is AgentMove.Act)
        assertFalse(AgentLoop.needsConfirmation(ScreenStep.Type("buy milk")))
        assertFalse(AgentLoop.needsConfirmation(ScreenStep.Open("Amazon")))
    }

    @Test
    fun `an explicit ask is passed through`() {
        val move = AgentLoop.parseMove("<<ASK|Which size do you want?>>")
        assertEquals(AgentMove.Ask("Which size do you want?"), move)
    }

    @Test
    fun `a reply with nothing usable is blocked, not guessed at`() {
        assertTrue(AgentLoop.parseMove("I'm not sure what to do here.") is AgentMove.Blocked)
        assertTrue(AgentLoop.parseMove("") is AgentMove.Blocked)
        assertTrue(AgentLoop.parseMove("<<ASK|>>") is AgentMove.Blocked)
    }

    @Test
    fun `the budget stops the loop`() {
        assertFalse(AgentLoop.exhausted(0))
        assertFalse(AgentLoop.exhausted(AgentLoop.MAX_STEPS - 1))
        assertTrue(AgentLoop.exhausted(AgentLoop.MAX_STEPS))
        assertTrue(AgentLoop.exhausted(AgentLoop.MAX_STEPS + 5))
    }

    @Test
    fun `running out of steps says so, and names the goal`() {
        val message = AgentLoop.exhaustedMessage("add milk to my Blinkit basket")
        assertTrue(message.contains("add milk to my Blinkit basket"))
        assertTrue("the user must know it stopped", message.contains("stopped"))
    }

    @Test
    fun `history stays short however long the errand runs`() {
        // It rides on every step's prompt. An unbounded transcript is what makes a
        // long errand hit the per-minute token limit halfway through.
        val many = (1..30).map { ScreenStep.Tap("Item $it") }
        val history = AgentLoop.historyOf(many)

        assertTrue("must summarise the earlier steps", history.contains("earlier"))
        assertTrue("recent steps are what matter", history.contains("Item 30"))
        assertFalse("the first steps are dropped", history.contains("Item 1,"))
        assertTrue("kept short, got ${history.length}", history.length < 300)
    }

    @Test
    fun `history reads as plain english for the model`() {
        assertEquals("nothing yet", AgentLoop.historyOf(emptyList<ScreenStep>()))
        assertEquals(
            "opened Blinkit, tapped Search, typed \"milk\", submitted",
            AgentLoop.historyOf(
                listOf(
                    ScreenStep.Open("Blinkit"),
                    ScreenStep.Tap("Search"),
                    ScreenStep.Type("milk"),
                    ScreenStep.Enter,
                ),
            ),
        )
    }
}
