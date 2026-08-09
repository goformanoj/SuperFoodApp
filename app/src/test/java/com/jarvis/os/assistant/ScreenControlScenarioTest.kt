package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenActions
import com.jarvis.os.control.ScreenStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rigorous scenario coverage for screen control — Part A (multi-step commands +
 * typing) and Part C (accuracy: the guards + the one-action-at-a-time errand loop).
 *
 * Two harnesses, both driving the SAME pure logic the engine uses on-device, so a
 * regression here is a regression there:
 *  - [plan] mirrors the one-shot guard chain in `AssistantEngine.ask()`
 *    (parse → AskGuard → SendGuard → SpendGuard) — Part A batch plans.
 *  - [driveErrand] mirrors `AssistantEngine.driveErrand()`: feed the model one reply
 *    at a time, take exactly the first action, look again — Part C long tasks. It
 *    accumulates `taken`/`avoid`/`stayInApp` exactly as the engine does, so the
 *    circuit-breakers (app-lock, repeat/stall, budget, irreversible-stop) are
 *    exercised over realistic multi-step errands.
 *
 * Device-only ceiling (Rule 5, NOT covered here): whether a tap actually lands on
 * the real control, real third-party app layouts, and the LLM's PICK choice. The
 * label-matching *scoring* that decides which control to tap is covered separately
 * in `control/ScreenMatchAccuracyTest`.
 */
class ScreenControlScenarioTest {

    // --- Harness: one-shot plan, mirroring AssistantEngine.ask() guard order ---
    private fun plan(userText: String, modelReply: String): List<ScreenStep> {
        val parsed = ScreenActions.parse(modelReply)
        val asked = AskGuard.apply(parsed.clean, parsed.steps)
        val guarded = SendGuard.apply(userText, asked)
        return SpendGuard.apply(userText, guarded)
    }

    // --- Harness: the one-action-at-a-time errand loop ---
    private data class Drive(val moves: List<AgentMove>, val taken: List<ScreenStep>, val outcome: String)

    /**
     * Feeds [replies] through [AgentLoop.parseMove] one at a time on the success
     * path (each Act is assumed to have worked, so `avoid` stays clear — failure
     * and retry are covered separately by driving parseMove directly). Stops on the
     * first Blocked/Ask/Done or when the step budget is spent, mirroring the engine.
     */
    private fun driveErrand(stayInApp: String?, replies: List<String>): Drive {
        val taken = mutableListOf<ScreenStep>()
        val moves = mutableListOf<AgentMove>()
        for (reply in replies) {
            if (AgentLoop.exhausted(taken.size)) return Drive(moves, taken, "exhausted")
            val move = AgentLoop.parseMove(reply, avoid = null, taken = taken, stayInApp = stayInApp)
            moves.add(move)
            when (move) {
                is AgentMove.Act -> taken.add(move.step)
                is AgentMove.Blocked -> return Drive(moves, taken, "blocked:${move.reason}")
                is AgentMove.Ask -> return Drive(moves, taken, "ask")
                AgentMove.Done -> return Drive(moves, taken, "done")
            }
        }
        return Drive(moves, taken, "ran-out")
    }

    // ============================ Part A — one-shot multi-step ============================

    @Test
    fun `a full search errand keeps all four steps in order`() {
        val steps = plan(
            userText = "show me a standup comedy video",
            modelReply = "<<OPEN|YouTube>> <<TAP|Search>> <<TYPE|standup comedy>> <<ENTER>>",
        )
        assertEquals(
            listOf(
                ScreenStep.Open("YouTube"),
                ScreenStep.Tap("Search"),
                ScreenStep.Type("standup comedy"),
                ScreenStep.Enter,
            ),
            steps,
        )
    }

    @Test
    fun `the opened app name is preserved verbatim for resolution`() {
        // "play Amazon Music" must not collapse to "Amazon" at the parse layer;
        // picking the right package from the name is AppLauncher.rank's job.
        assertEquals(listOf(ScreenStep.Open("Amazon Music")), plan("play amazon music", "<<OPEN|Amazon Music>>"))
    }

    @Test
    fun `compose without send drops only the trailing submit`() {
        val steps = plan(
            userText = "type good morning to mom on whatsapp",
            modelReply = "<<OPEN|WhatsApp>> <<TAP|Mom>> <<TYPE|good morning>> <<ENTER>>",
        )
        assertEquals(listOf(ScreenStep.Open("WhatsApp"), ScreenStep.Tap("Mom"), ScreenStep.Type("good morning")), steps)
    }

    @Test
    fun `an explicit send keeps the submit`() {
        val steps = plan(
            userText = "send good morning to mom on whatsapp",
            modelReply = "<<OPEN|WhatsApp>> <<TAP|Mom>> <<TYPE|good morning>> <<ENTER>>",
        )
        assertTrue("send authorised → ENTER kept", steps.contains(ScreenStep.Enter))
    }

    @Test
    fun `a mid-sequence submit is a search step and survives even when composing`() {
        // "type" is present, but the ENTER here is submitting a search, not sending
        // a message — only a TRAILING submit is dropped, so this one stays.
        val steps = plan(
            userText = "search youtube and type a comment",
            modelReply = "<<OPEN|YouTube>> <<TAP|Search>> <<TYPE|cats>> <<ENTER>> <<TAP|Comment>> <<TYPE|nice>>",
        )
        assertTrue("mid-sequence ENTER kept", steps.contains(ScreenStep.Enter))
    }

    @Test
    fun `an unrequested checkout is withheld while the recoverable steps run`() {
        val steps = plan(
            userText = "add bread and milk on blinkit",
            modelReply = "<<OPEN|Blinkit>> <<TAP|Search>> <<TYPE|bread>> <<ENTER>> <<TAP|Add>> " +
                "<<TAP|Search>> <<TYPE|milk>> <<ENTER>> <<TAP|Add>> <<TAP|Checkout>>",
        )
        assertFalse("checkout withheld", steps.contains(ScreenStep.Tap("Checkout")))
        assertEquals("two 'Add' taps still run", 2, steps.count { it == ScreenStep.Tap("Add") })
    }

    @Test
    fun `an explicit purchase is allowed through to checkout`() {
        val steps = plan(
            userText = "buy bread on blinkit and check out",
            modelReply = "<<OPEN|Blinkit>> <<TAP|Search>> <<TYPE|bread>> <<ENTER>> <<TAP|Add>> <<TAP|Checkout>>",
        )
        assertTrue("purchase authorised → checkout kept", steps.contains(ScreenStep.Tap("Checkout")))
    }

    @Test
    fun `asking and acting at once drops every step`() {
        val steps = plan(
            userText = "play some music",
            modelReply = "I can do that. Which app would you like me to use?\n<<OPEN|YouTube>>",
        )
        assertEquals(emptyList<ScreenStep>(), steps)
    }

    @Test
    fun `a long in-app navigation with no irreversible tap passes through intact`() {
        val reply = "<<OPEN|Settings>> <<TAP|Network>> <<TAP|Wi-Fi>> <<TAP|Advanced>> <<TAP|Saved networks>> <<BACK>>"
        val steps = plan("open my saved wifi networks", reply)
        assertEquals(6, steps.size)
        assertEquals(ScreenStep.Open("Settings"), steps.first())
        assertEquals(ScreenStep.Back, steps.last())
    }

    // ============================ Part C — long continuous errands ============================

    @Test
    fun `a happy errand runs every step then finishes on DONE`() {
        val d = driveErrand(
            stayInApp = "YouTube",
            replies = listOf(
                "<<OPEN|YouTube>>",
                "Tapping search. <<TAP|Search>>",
                "<<TYPE|lofi beats>>",
                "<<ENTER>>",
                "<<PICK|the first video result>>",
                "That's playing now. <<DONE>>",
            ),
        )
        assertEquals("done", d.outcome)
        assertEquals(5, d.taken.size)
        assertTrue(d.moves.dropLast(1).all { it is AgentMove.Act })
        assertEquals(ScreenStep.Pick("the first video result"), d.taken.last())
    }

    @Test
    fun `the app-lock stops an errand wandering into an unrelated app`() {
        val d = driveErrand(
            stayInApp = "WhatsApp",
            replies = listOf("<<OPEN|WhatsApp>>", "<<TAP|Mom>>", "Calling instead. <<OPEN|Phone>>"),
        )
        assertEquals("blocked:${AgentLoop.LEFT_APP}", d.outcome)
        assertEquals(2, d.taken.size) // it acted twice before the block
    }

    @Test
    fun `re-opening the same app to refine is allowed, not treated as leaving`() {
        // "Amazon" ⊆ "Amazon Music": a relaunch/refine of the same app is fine.
        val move = AgentLoop.parseMove("<<OPEN|Amazon>>", stayInApp = "Amazon Music")
        assertTrue(move is AgentMove.Act)
    }

    @Test
    fun `repeating a step that changed nothing breaks the loop`() {
        val d = driveErrand(
            stayInApp = "Blinkit",
            replies = listOf("<<OPEN|Blinkit>>", "<<TAP|Search for atta, dal, coke and more>>", "<<TAP|Search for atta, dal, coke and more>>"),
        )
        assertEquals("blocked:${AgentLoop.GOING_IN_CIRCLES}", d.outcome)
    }

    @Test
    fun `re-picking the step that just failed is refused`() {
        val move = AgentLoop.parseMove("<<TAP|Search>>", avoid = ScreenStep.Tap("Search"))
        assertTrue(move is AgentMove.Blocked)
        assertEquals(AgentLoop.ALREADY_FAILED, (move as AgentMove.Blocked).reason)
    }

    @Test
    fun `the errand stops at its step budget instead of tapping forever`() {
        val replies = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I").map { "<<TAP|$it>>" }
        val d = driveErrand(stayInApp = null, replies = replies)
        assertEquals("exhausted", d.outcome)
        assertEquals(AgentLoop.MAX_STEPS, d.taken.size)
    }

    @Test
    fun `an irreversible step mid-errand asks first instead of acting`() {
        val d = driveErrand(
            stayInApp = "Amazon",
            replies = listOf("<<OPEN|Amazon>>", "<<TAP|Item>>", "<<TAP|Place order>>"),
        )
        assertEquals("ask", d.outcome)
        assertEquals(2, d.taken.size) // opened + tapped item, then paused to confirm
        assertTrue(d.moves.last() is AgentMove.Ask)
    }

    @Test
    fun `DONE alongside an action is not done — the action wins`() {
        val move = AgentLoop.parseMove("<<TAP|Search>> <<DONE>>")
        assertEquals(AgentMove.Act(ScreenStep.Tap("Search")), move)
    }

    @Test
    fun `an ASK marker surfaces as a question, not an action`() {
        val move = AgentLoop.parseMove("<<ASK|Which of the two Johns did you mean?>>")
        assertTrue(move is AgentMove.Ask)
        assertEquals("Which of the two Johns did you mean?", (move as AgentMove.Ask).question)
    }

    @Test
    fun `a reply with no step and no DONE is a speakable dead-end, not a crash`() {
        val move = AgentLoop.parseMove("I can't see a cart anywhere on this screen.")
        assertTrue(move is AgentMove.Blocked)
        // The model's own words are preferred, and they're speakable (not an internal tag).
        val spoken = AgentLoop.blockedMessage("add bread", (move as AgentMove.Blocked).reason)
        assertFalse(spoken.contains(AgentLoop.NO_STEP))
        assertTrue(spoken.contains("cart"))
    }

    @Test
    fun `isErrand routes only open-then-interact tasks to the driven loop`() {
        assertTrue(AgentLoop.isErrand(ScreenActions.parse("<<OPEN|YouTube>> <<TAP|Search>> <<TYPE|x>> <<ENTER>>").steps))
        assertFalse("a single open is not an errand", AgentLoop.isErrand(listOf(ScreenStep.Open("YouTube"))))
        assertFalse("an in-app follow-up is not an errand", AgentLoop.isErrand(listOf(ScreenStep.Tap("Mom"))))
    }
}
