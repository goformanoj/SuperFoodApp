package com.jarvis.os.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accuracy scenarios for the "tap the right control" decision — the scoring that,
 * on-device, chooses which node `seek` taps. Modelled as: given a screen of controls
 * (visible text + content-description) and a query label, the highest [ScreenMatch]
 * score wins, and a real target must clear the service's GOOD_SCORE gate.
 *
 * This is the pure half of Part C. The other half (walking the live accessibility
 * tree, scrolling, verifying the tap landed) is device-only (Rule 5).
 */
class ScreenMatchAccuracyTest {

    /** Mirrors ScreenControlService.GOOD_SCORE — a match at/above this is tapped confidently. */
    private val GOOD_SCORE = 70

    private data class Control(val text: String, val desc: String = "")

    /** The best-scoring control for [query] and its score, as `seek`/`bestMatch` would pick. */
    private fun best(screen: List<Control>, query: String): Pair<Control?, Int> {
        val q = query.trim().lowercase()
        var winner: Control? = null
        var top = 0
        for (c in screen) {
            val s = ScreenMatch.matchScore(c.text, c.desc, q)
            if (s > top) { top = s; winner = c }
        }
        return winner to top
    }

    @Test
    fun `exact label wins over a longer prefix match and a description-only match`() {
        val screen = listOf(
            Control("Search"),
            Control("Search YouTube"),
            Control("Settings"),
            Control("Shorts"),
            Control(text = "", desc = "Search"),
        )
        val (winner, score) = best(screen, "Search")
        assertEquals("Search", winner?.text)
        assertEquals(100, score)
        assertTrue("a confident match clears the tap gate", score >= GOOD_SCORE)
    }

    @Test
    fun `a word-boundary prefix is chosen over a mere substring, and the substring is below the gate`() {
        val screen = listOf(Control("Mom (Home)"), Control("Moment"), Control("Dad"))
        val (winner, score) = best(screen, "mom")
        assertEquals("Mom (Home)", winner?.text)
        assertTrue(score >= GOOD_SCORE)
        // "Moment" only *contains* "mom" — it must not be a confident tap target.
        assertTrue("substring match stays below the gate", ScreenMatch.matchScore("Moment", "", "mom") < GOOD_SCORE)
    }

    @Test
    fun `visible text beats content-description at the same tier`() {
        // Exact on text (100) must outrank exact on a different control's desc (85).
        val screen = listOf(Control(text = "", desc = "Play"), Control("Play"))
        val (winner, _) = best(screen, "play")
        assertEquals("Play", winner?.text)
    }

    @Test
    fun `an icon button with only a content-description is still findable`() {
        // A bare icon (no visible text) exposes its label via contentDescription.
        val score = ScreenMatch.matchScore(text = "", desc = "Send", query = "send")
        assertEquals(85, score)
        assertTrue(score >= GOOD_SCORE)
    }

    @Test
    fun `nothing on the screen matches — no confident target`() {
        val screen = listOf(Control("Home"), Control("Profile"), Control("Notifications"))
        val (_, score) = best(screen, "checkout")
        assertTrue("no match must fall below the gate so seek won't tap blindly", score < GOOD_SCORE)
    }

    @Test
    fun `case and surrounding whitespace do not change the match`() {
        assertEquals(
            ScreenMatch.matchScore("Search", "", "search"),
            ScreenMatch.matchScore("   SEARCH  ", "", "search"),
        )
    }

    // --- redaction accuracy: credentials must never leave the device (Part C privacy) ---

    @Test
    fun `a one-time code in screen text is masked`() {
        assertEquals("Your verification code is ***", ScreenMatch.redactSensitive("Your verification code is 481922", isPassword = false))
    }

    @Test
    fun `every one-time code in a line is masked, not just the first`() {
        assertEquals("codes *** and ***", ScreenMatch.redactSensitive("codes 1234 and 5678", isPassword = false))
    }

    @Test
    fun `a password field is replaced wholesale regardless of contents`() {
        assertEquals("***", ScreenMatch.redactSensitive("hunter2!", isPassword = true))
    }

    @Test
    fun `ordinary numbers that are not codes are left readable`() {
        // 3 digits (too short) and a 10-digit phone run (too long) are not OTP-shaped.
        assertEquals("Order 12 items", ScreenMatch.redactSensitive("Order 12 items", isPassword = false))
        assertEquals("call 1234567890", ScreenMatch.redactSensitive("call 1234567890", isPassword = false))
    }
}
