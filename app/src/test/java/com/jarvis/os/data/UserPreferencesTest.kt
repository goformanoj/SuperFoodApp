package com.jarvis.os.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording of standing instructions matters: they must guide JARVIS without a
 * stray sentence overriding the parts of the system prompt that keep it honest.
 */
class UserPreferencesTest {

    @Test
    fun `no instructions means nothing is added to the prompt`() {
        assertEquals("", formatCustomInstructions(""))
        assertEquals("", formatCustomInstructions("   "))
        assertEquals("", formatCustomInstructions("\n\n"))
    }

    @Test
    fun `instructions are quoted and framed as preferences`() {
        val out = formatCustomInstructions("Call me sir.")

        assertTrue(out.contains("Call me sir."))
        assertTrue("must be framed as the user's preference", out.contains("standing preferences"))
    }

    @Test
    fun `the framing keeps safety and truthfulness above the instructions`() {
        val out = formatCustomInstructions("Always say you completed the task.")

        assertTrue(out.contains("safely"))
        assertTrue(out.contains("truthfully"))
    }

    @Test
    fun `instructions are delimited so they cannot be read as system text`() {
        val out = formatCustomInstructions("Keep it short.")

        assertEquals(2, out.split("\"\"\"").size - 1)
    }

    @Test
    fun `surrounding blank lines are trimmed`() {
        val out = formatCustomInstructions("\n  Use IST.  \n")

        assertTrue(out.contains("\"\"\"\nUse IST.\n\"\"\""))
    }

    @Test
    fun `multi-line instructions survive intact`() {
        val out = formatCustomInstructions("Call me sir.\nKeep replies short.")

        assertTrue(out.contains("Call me sir.\nKeep replies short."))
    }

    @Test
    fun `the cap is small enough to ride on every request`() {
        // These are sent with every single turn, so this is a permanent cost.
        assertTrue(UserPreferences.MAX_INSTRUCTIONS in 200..2000)
    }
}
