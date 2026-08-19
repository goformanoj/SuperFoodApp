package com.jarvis.os.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * From a device trace, and this is the failure the whole type exists for:
 *
 * ```
 * 16:48:13  agent stopped to ask: I'm about to tap Send, which I can't undo. Shall I?
 * 16:48:27  (voice) do it
 * 16:48:29  REPLY: <<TYPE|Got it! Let me know if you need anything else.>> <<TAP|Send>>
 * 16:48:34  step 1/2 Type(text=Got it! Let me know if you need anything else.)
 * 16:48:34  step 2/2 Tap(label=Send)
 * ```
 *
 * The user had asked for a thoughtful message, JARVIS wrote one, then on "do it"
 * the model invented a completely different message, typed that, and sent it.
 * Messages do not come back.
 */
class ConfirmationTest {

    @Test
    fun `the words from the trace are a yes`() {
        assertTrue(Confirmation.isYes("do it"))
        assertTrue(Confirmation.isYes("Do it."))
        assertTrue(Confirmation.isYes("DO IT!"))
    }

    @Test
    fun `the ordinary ways of agreeing are a yes`() {
        listOf("yes", "yeah", "yep", "sure", "ok", "okay", "go ahead", "send it", "confirm")
            .forEach { assertTrue("\"$it\" should be yes", Confirmation.isYes(it)) }
    }

    @Test
    fun `the ordinary ways of refusing are a no`() {
        listOf("no", "nope", "stop", "cancel", "don't", "leave it", "never mind", "wait")
            .forEach { assertTrue("\"$it\" should be no", Confirmation.isNo(it)) }
    }

    // --- the part that keeps this safe ---------------------------------------

    @Test
    fun `a yes with a condition attached is NOT a yes`() {
        // The dangerous direction. "yes but change the wording" contains "yes",
        // and treating it as permission sends the wrong message — the exact bug
        // this exists to prevent. It has to fall through to the model instead.
        assertEquals(Confirmation.Answer.NEITHER, Confirmation.answerFor("yes but change the wording first"))
        assertEquals(Confirmation.Answer.NEITHER, Confirmation.answerFor("ok but say something nicer"))
        assertEquals(Confirmation.Answer.NEITHER, Confirmation.answerFor("do it but wait for me"))
    }

    @Test
    fun `a sentence that merely contains no is not a refusal`() {
        assertFalse(Confirmation.isNo("no idea, what do you think"))
        assertFalse(Confirmation.isNo("nothing else for now"))
    }

    @Test
    fun `an ordinary request is neither`() {
        // The common case, and guessing either way is worse than both: yes fires
        // an irreversible action nobody authorised, no silently drops something
        // the user asked for.
        listOf(
            "open youtube",
            "what's the weather",
            "reply with something friendlier",
            "read the screen for me",
        ).forEach {
            assertEquals("\"$it\"", Confirmation.Answer.NEITHER, Confirmation.answerFor(it))
        }
    }

    @Test
    fun `punctuation and casing do not matter`() {
        assertEquals(Confirmation.Answer.YES, Confirmation.answerFor("  Yes, please!  "))
        assertEquals(Confirmation.Answer.NO, Confirmation.answerFor("No — thanks."))
    }

    @Test
    fun `empty input is neither`() {
        assertEquals(Confirmation.Answer.NEITHER, Confirmation.answerFor(""))
        assertEquals(Confirmation.Answer.NEITHER, Confirmation.answerFor("   "))
    }
}
