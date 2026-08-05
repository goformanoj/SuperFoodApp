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
    fun `the step that just failed is never repeated`() {
        // From a device trace: told Tap("Search for atta, dal, coke and more")
        // had failed, the model answered with that exact tap three times running
        // while the user watched. The prompt already forbids it; this makes it
        // true regardless.
        val failed = ScreenStep.Tap("Search for atta, dal, coke and more")
        val move = AgentLoop.parseMove("<<TAP|Search for atta, dal, coke and more>>", avoid = failed)
        assertTrue("must not repeat the failed step, got $move", move is AgentMove.Blocked)
    }

    @Test
    fun `a different step after a failure is allowed`() {
        val failed = ScreenStep.Tap("Search")
        assertEquals(
            AgentMove.Act(ScreenStep.Type("bread")),
            AgentLoop.parseMove("<<TYPE|bread>>", avoid = failed),
        )
    }

    @Test
    fun `an errand is open-then-interact, and is driven rather than planned`() {
        // The shape that cannot be planned up front: the second action depends on
        // what the first one revealed.
        assertTrue(
            AgentLoop.isErrand(
                listOf(
                    ScreenStep.Open("Blinkit"),
                    ScreenStep.Tap("Search"),
                    ScreenStep.Type("bread"),
                    ScreenStep.Enter,
                ),
            ),
        )
    }

    @Test
    fun `simple commands are left on the fast path`() {
        // These are planned against the screen the user is actually looking at,
        // they work today, and a round-trip per action would only slow them down.
        assertFalse("just opening an app", AgentLoop.isErrand(listOf(ScreenStep.Open("YouTube"))))
        assertFalse(
            "open and one action",
            AgentLoop.isErrand(listOf(ScreenStep.Open("YouTube"), ScreenStep.Tap("Search"))),
        )
        assertFalse(
            "a follow-up in the app already open",
            AgentLoop.isErrand(listOf(ScreenStep.Tap("Search"), ScreenStep.Type("bread"), ScreenStep.Enter)),
        )
    }

    @Test
    fun `only the leading opens run before the first look`() {
        val steps = listOf(
            ScreenStep.Open("Blinkit"),
            ScreenStep.Tap("Search"),
            ScreenStep.Type("bread"),
        )
        assertEquals(listOf(ScreenStep.Open("Blinkit")), AgentLoop.opensIn(steps))
    }

    @Test
    fun `the budget is small, because a big one just buys more wrong taps`() {
        // This test used to assert MAX_STEPS >= 14, and that was the mistake: a
        // device session spent all eighteen on one shopping app — "Use my
        // Current Location" four times, "Search for" five times — and the user's
        // verdict was "it just clicked sooo many random buttons". Stopping when
        // nothing changes is the fix; a bigger allowance is the opposite of it.
        assertTrue("a confused loop must be cheap", AgentLoop.MAX_STEPS <= 10)
    }

    @Test
    fun `a step that just succeeded is not chosen again`() {
        // The thrashing was NOT failed steps. Open(Zepto) "succeeded" three times
        // running, each logged "already in Zepto — not relaunching". Success is
        // not progress.
        val taken = listOf(ScreenStep.Open("Zepto"))
        val move = AgentLoop.parseMove("<<OPEN|Zepto>>", taken = taken)
        assertTrue("must not reopen what is already open, got $move", move is AgentMove.Blocked)
    }

    @Test
    fun `a step tried twice already is refused`() {
        val taken = listOf(
            ScreenStep.Tap("Use my Current Location"),
            ScreenStep.Back,
            ScreenStep.Tap("Use my Current Location"),
            ScreenStep.Back,
        )
        assertTrue(AgentLoop.repeats(ScreenStep.Tap("Use my Current Location"), taken))
        val move = AgentLoop.parseMove("<<TAP|Use my Current Location>>", taken = taken)
        assertTrue(move is AgentMove.Blocked)
    }

    @Test
    fun `a fresh step is still allowed after other steps have been tried`() {
        val taken = listOf(ScreenStep.Open("Zepto"), ScreenStep.Tap("Search"))
        assertEquals(
            AgentMove.Act(ScreenStep.Type("bread")),
            AgentLoop.parseMove("<<TYPE|bread>>", taken = taken),
        )
    }

    @Test
    fun `internal reasons are never read aloud`() {
        // A trace has JARVIS saying "that step has already failed here. I've
        // stopped there rather than guess." to someone who cannot see the log.
        AgentLoop.INTERNAL_REASONS.forEach { reason ->
            val message = AgentLoop.blockedMessage("add bread", reason)
            assertFalse("'$reason' must not be spoken", message.contains(reason))
            assertTrue(message.contains("add bread"))
        }
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
    fun `a dead end is always spoken, never just logged`() {
        // Silence is the failure being fixed here: a loop that gives up quietly
        // is indistinguishable from one still working, so the user waits and
        // then asks again. Every reason, however useless, must yield speech.
        listOf(AgentLoop.NO_STEP, "", "   ", "Hmm", "?", "I can't see a cart on this screen")
            .forEach { reason ->
                val message = AgentLoop.blockedMessage("add milk to my basket", reason)
                assertTrue("nothing to say for reason '$reason'", message.isNotBlank())
                assertTrue("must hand back to the user", message.endsWith("What next?"))
                assertFalse(
                    "the internal placeholder must never be spoken",
                    message.contains(AgentLoop.NO_STEP),
                )
            }
    }

    @Test
    fun `the model's own explanation is preferred when it gave one`() {
        // "I can't see a cart on this screen" tells the user something a
        // generic line cannot.
        val message = AgentLoop.blockedMessage("add milk", "I can't see a cart on this screen")
        assertTrue(message.startsWith("I can't see a cart on this screen."))
    }

    @Test
    fun `a fragment is not read aloud, the goal is named instead`() {
        // A stray word spoken back is worse than a plain admission.
        val message = AgentLoop.blockedMessage("add milk to my basket", "Hmm")
        assertFalse(message.startsWith("Hmm"))
        assertTrue("the user needs to know which errand stopped", message.contains("add milk to my basket"))
    }

    @Test
    fun `an explanation that already ends in punctuation is not double-stopped`() {
        val message = AgentLoop.blockedMessage("add milk", "There is no Add button here!")
        assertTrue(message.startsWith("There is no Add button here! I've stopped"))
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
