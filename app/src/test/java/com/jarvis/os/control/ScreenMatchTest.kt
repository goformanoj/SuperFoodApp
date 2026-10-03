package com.jarvis.os.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM cover for the "tap the right control" scoring and the credential
 * redaction — the accuracy-critical logic that used to be checkable only on a
 * device. No Android runtime: [ScreenMatch] is plain strings in, ints/strings out.
 */
class ScreenMatchTest {

    // --- normalizeLabel: strip an appended argument the model adds to a tap ---

    @Test
    fun `a trailing quoted argument is dropped from a tap label`() {
        // The device trace that motivated this: <<TAP|Search "milk">> matched no
        // control, because the control is just "Search".
        assertEquals("Search", ScreenMatch.normalizeLabel("""Search "milk""""))
        assertEquals("Search", ScreenMatch.normalizeLabel("""Search "organic honey""""))
    }

    @Test
    fun `a fully quoted label is unwrapped`() {
        assertEquals("Search", ScreenMatch.normalizeLabel(""""Search""""))
        assertEquals("milk", ScreenMatch.normalizeLabel(""""milk""""))
    }

    @Test
    fun `a plain label is returned untouched, apostrophes included`() {
        assertEquals("Add to cart", ScreenMatch.normalizeLabel("Add to cart"))
        assertEquals("Add to cart", ScreenMatch.normalizeLabel("  Add to cart  "))
        // An apostrophe is not a quote — a real contact/label must keep it.
        assertEquals("Mom's chat", ScreenMatch.normalizeLabel("Mom's chat"))
    }

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

    // --- Device trace 2026-10-03 (realme, real Blinkit): "go to blinkit and add bread to my cart" ---------------------

    @Test
    fun blinkitsRealSearchBoxMatchesTheGenericWordSearch() {
        // The home screen's search box, exactly as accessibility exposes it: the label is a child TextView's text.
        // Once drawn it scores far above the tap threshold (70), so the first step's failure was never the matcher.
        val score = ScreenMatch.matchScore("Search for atta, dal, coke and more", "", "search")
        assertTrue("score $score", score >= 70)
        // The bottom-bar "Categories" tab must not be a candidate for "Search".
        assertEquals(0, ScreenMatch.matchScore("Categories", "", "search"))
    }

    @Test
    fun aScreenStillFillingInIsWaitedForUntilItSettlesOrTheBudgetRuns() {
        // First look: nothing to compare with yet, so look once more.
        assertTrue(ScreenMatch.keepWaitingForScreen(0, 10, null, "shimmer"))
        // The text changed since the last look: it is still arriving.
        assertTrue(ScreenMatch.keepWaitingForScreen(3, 10, "shimmer", "Blinkit in 8 minutes"))
        // Unchanged between two looks: settled, the control really is missing — stop waiting.
        assertFalse(ScreenMatch.keepWaitingForScreen(3, 10, "Blinkit in 8 minutes", "Blinkit in 8 minutes"))
        // A screen that never stops changing (a ticking banner) is not waited on forever.
        assertFalse(ScreenMatch.keepWaitingForScreen(10, 10, "a", "b"))
    }

    @Test
    fun keyboardTypingCoversLettersAndSpacesOnly() {
        assertEquals(listOf("b", "r", "e", "a", "d"), ScreenMatch.keyboardLabels("bread"))
        assertEquals(listOf("m", "i", "l", "k", "Space", "a"), ScreenMatch.keyboardLabels("Milk a"))
        // A digit or symbol needs another keyboard page, so the caller must not pretend it can type it.
        assertEquals(null, ScreenMatch.keyboardLabels("5 litre milk"))
        assertEquals(null, ScreenMatch.keyboardLabels("   "))
        assertEquals(null, ScreenMatch.keyboardLabels("दूध"))
    }
}
