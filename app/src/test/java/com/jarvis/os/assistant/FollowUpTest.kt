package com.jarvis.os.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cases that matter are the two failure directions, and they pull opposite
 * ways: missing a real question is the bug from the 2026-08-26 trace, and
 * inventing one means an unasked-for second sentence after every command.
 */
class FollowUpTest {

    // --- the trace itself -------------------------------------------------

    @Test
    fun `the request from the trace carries a question past the action`() {
        assertEquals(
            "tell me if i have any classes today",
            FollowUp.questionIn("can you open the pw app and tell me if i have any classes today"),
        )
    }

    @Test
    fun `a question word inside the action half is not the question`() {
        // "can you open…" opens with an auxiliary and is answered by opening
        // the app, not by talking. If this ever returns non-null, JARVIS
        // answers its own instruction.
        assertNull(FollowUp.questionIn("can you open whatsapp"))
    }

    // --- must stay silent -------------------------------------------------

    @Test
    fun `a bare action has nothing left to answer`() {
        assertNull(FollowUp.questionIn("open whatsapp"))
        assertNull(FollowUp.questionIn("open spotify and play something"))
        assertNull(FollowUp.questionIn("open whatsapp and send hi to mom"))
    }

    @Test
    fun `a bare question is answered by the normal reply path`() {
        assertNull(FollowUp.questionIn("do i have classes today"))
        assertNull(FollowUp.questionIn("what's the weather like"))
    }

    @Test
    fun `telling someone else is an errand, not a question`() {
        // "tell her" is not "tell me". Reading this as a question would have
        // JARVIS answering a message it was asked to send.
        assertNull(FollowUp.questionIn("message mom and tell her i'll be late"))
    }

    @Test
    fun `an auxiliary with no first person is not a question`() {
        assertNull(FollowUp.questionIn("open the gallery and have a look"))
    }

    @Test
    fun `a question asked before the action is not deferred`() {
        // The ordinary reply answers it on the way past; holding it would
        // answer it twice.
        assertNull(FollowUp.questionIn("what's the score, then open youtube"))
    }

    @Test
    fun `a fragment too short to answer is dropped`() {
        assertNull(FollowUp.questionIn("open pw and tell me"))
    }

    // --- must speak -------------------------------------------------------

    @Test
    fun `check and see and find out all ask to be told something`() {
        assertEquals(
            "check if i have any classes today",
            FollowUp.questionIn("open the pw app and check if i have any classes today"),
        )
        assertEquals(
            "see if there are any new messages",
            FollowUp.questionIn("open whatsapp and see if there are any new messages"),
        )
        // "find" is an action verb; "find out" is not. The phrase wins.
        assertEquals(
            "find out when my next class is",
            FollowUp.questionIn("open pw and find out when my next class is"),
        )
    }

    @Test
    fun `a plain question after an action is kept`() {
        assertEquals(
            "do i have any classes today",
            FollowUp.questionIn("open the pw app, do i have any classes today"),
        )
    }

    @Test
    fun `the whole tail is kept, connectives and all`() {
        assertEquals(
            "tell me if i have classes and when the first one starts",
            FollowUp.questionIn(
                "open pw and tell me if i have classes and when the first one starts",
            ),
        )
    }

    @Test
    fun `filler in front of the question is trimmed`() {
        assertEquals(
            "tell me the balance",
            FollowUp.questionIn("open the bank app, also tell me the balance"),
        )
    }

    @Test
    fun `the user's own wording and casing survive`() {
        assertEquals(
            "tell me if I have any classes today?",
            FollowUp.questionIn("Open the PW app and tell me if I have any classes today?"),
        )
    }

    @Test
    fun `an action after the question keeps only the question`() {
        assertEquals(
            "tell me the score",
            FollowUp.questionIn("open the cricket app and tell me the score then open youtube"),
        )
    }
}
