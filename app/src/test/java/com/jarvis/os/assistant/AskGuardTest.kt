package com.jarvis.os.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * From a device trace: the reply asked "what app would you like to use, or
 * should I open YouTube?" and opened YouTube in the same breath. The user's next
 * words were "you ask me for the option but you still open YouTube".
 */
class AskGuardTest {

    private val steps = listOf("Open(YouTube)", "Tap(Search)")

    @Test
    fun `the trace's ask-and-act is suppressed`() {
        val spoken = "I'll get that playing for you, what app would you like to use, " +
            "or should I open YouTube?"
        assertTrue(AskGuard.asksQuestion(spoken))
        assertEquals(emptyList<String>(), AskGuard.apply(spoken, steps))
    }

    @Test
    fun `a plain statement still acts`() {
        listOf(
            "Sure, opening YouTube.",
            "On it.",
            "Playing that now.",
            "I'll pull up the chat.",
        ).forEach {
            assertFalse(AskGuard.asksQuestion(it))
            assertEquals(steps, AskGuard.apply(it, steps))
        }
    }

    @Test
    fun `only the final sentence counts`() {
        // A question asked and then answered is not a request for input.
        val spoken = "Which one did you mean? The Michael Jackson one, so I'm opening that."
        assertFalse(AskGuard.asksQuestion(spoken))
        assertEquals(steps, AskGuard.apply(spoken, steps))
    }

    @Test
    fun `a one-word question does not block an action`() {
        // "Ready?" is a flourish, not a decision the user has to make.
        assertFalse(AskGuard.asksQuestion("Ready?"))
        assertEquals(steps, AskGuard.apply("Ready?", steps))
    }

    @Test
    fun `no steps and no reply are not errors`() {
        // The type argument is explicit because AskGuard.apply is generic: a bare
        // emptyList() gives the compiler nothing to infer T from.
        assertEquals(emptyList<String>(), AskGuard.apply("Shall I open it for you?", emptyList<String>()))
        assertFalse(AskGuard.asksQuestion(""))
        assertFalse(AskGuard.asksQuestion("   "))
    }
}
