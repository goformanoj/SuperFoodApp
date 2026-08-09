package com.jarvis.os.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM cover for the "tap the right control" scoring and the credential
 * redaction — the accuracy-critical logic that used to be checkable only on a
 * device. No Android runtime: [ScreenMatch] is plain strings in, ints/strings out.
 */
class ScreenMatchTest {

    // --- fieldScore tiers (inputs are already normalised, as callers guarantee) ---

    @Test
    fun `exact match scores highest, and visible text beats content-description`() {
        assertEquals(100, ScreenMatch.fieldScore("settings", "settings", isText = true))
        assertEquals(85, ScreenMatch.fieldScore("settings", "settings", isText = false))
    }

    @Test
    fun `score tiers rank exact over prefix over word over substring`() {
        val exact = ScreenMatch.fieldScore("mom", "mom", isText = true)
        val prefix = ScreenMatch.fieldScore("mom (dad)", "mom", isText = true)
        val word = ScreenMatch.fieldScore("call mom now", "mom", isText = true)
        val substr = ScreenMatch.fieldScore("moment", "mom", isText = true)
        assertTrue("exact > prefix > word > substring, got $exact/$prefix/$word/$substr",
            exact > prefix && prefix > word && word > substr && substr > 0)
    }

    @Test
    fun `no match scores zero`() {
        assertEquals(0, ScreenMatch.fieldScore("calendar", "mom", isText = true))
        assertEquals(0, ScreenMatch.fieldScore("", "mom", isText = true))
    }

    // --- word-boundary rules ---

    @Test
    fun `startsWithWord respects word boundaries but not apostrophes`() {
        assertTrue(ScreenMatch.startsWithWord("mom (dad)", "mom"))
        assertTrue(ScreenMatch.startsWithWord("mom", "mom"))
        assertTrue("apostrophe is not a boundary", !ScreenMatch.startsWithWord("mom's status", "mom"))
        assertTrue("letter is not a boundary", !ScreenMatch.startsWithWord("moment", "mom"))
    }

    @Test
    fun `containsWord finds a standalone word anywhere, not a substring`() {
        assertTrue(ScreenMatch.containsWord("call mom now", "mom"))
        assertTrue(ScreenMatch.containsWord("(mom)", "mom"))
        assertTrue("substring inside a word must not count", !ScreenMatch.containsWord("moment", "mom"))
    }

    // --- matchScore: reads text vs content-description, normalises internally ---

    @Test
    fun `matchScore normalises case and whitespace and prefers visible text`() {
        // Visible text exact (100) beats content-description prefix (60).
        assertEquals(100, ScreenMatch.matchScore("  Search  ", "Search button", "search"))
        // Falls back to the content-description when there is no visible text.
        assertEquals(85, ScreenMatch.matchScore("", "Settings", "settings"))
    }

    // --- redaction ---

    @Test
    fun `redactSensitive masks one-time codes and replaces password fields wholesale`() {
        assertEquals("your code is ***", ScreenMatch.redactSensitive("your code is 123456", isPassword = false))
        assertEquals("***", ScreenMatch.redactSensitive("hunter2", isPassword = true))
    }

    @Test
    fun `redactSensitive leaves short and over-long digit runs alone`() {
        // The OTP heuristic is a bare 4-8 digit run; 3 digits and a 9-digit run are left as-is.
        assertEquals("pin 123", ScreenMatch.redactSensitive("pin 123", isPassword = false))
        assertEquals("id 123456789", ScreenMatch.redactSensitive("id 123456789", isPassword = false))
    }
}
