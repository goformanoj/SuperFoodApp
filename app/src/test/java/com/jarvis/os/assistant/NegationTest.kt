package com.jarvis.os.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole-word, negation-aware trigger match the safety guards share. */
class NegationTest {

    @Test
    fun `an un-negated trigger is found`() {
        assertTrue(Negation.hasUnnegated("please check out now", "check out"))
        assertTrue(Negation.hasUnnegated("send it to mom", "send"))
        assertTrue(Negation.hasUnnegated("set an alarm", "alarm"))
    }

    @Test
    fun `a negator just before the trigger cancels it`() {
        assertFalse(Negation.hasUnnegated("add apples but dont check out", "check out"))
        assertFalse(Negation.hasUnnegated("do not send it", "send"))
        assertFalse(Negation.hasUnnegated("never buy this", "buy"))
        assertFalse(Negation.hasUnnegated("checkout without paying", "paying"))
        assertFalse(Negation.hasUnnegated("dont pay", "pay"))
    }

    @Test
    fun `a negator outside the window does not reach the trigger`() {
        // "dont" is four words before "alarm" — too far to cancel it.
        assertTrue(Negation.hasUnnegated("dont forget to set an alarm", "alarm"))
    }

    @Test
    fun `a later un-negated occurrence still counts`() {
        assertTrue(Negation.hasUnnegated("dont buy the first but buy the second", "buy"))
    }

    @Test
    fun `matching is whole-word, not substring`() {
        assertFalse(Negation.hasUnnegated("typewriter", "type"))
        assertFalse(Negation.hasUnnegated("payments help", "pay"))
    }
}
