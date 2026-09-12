package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The token-allowance arithmetic shown in the drawer. Pure, so it runs off-device:
 * the cap per plan, tokens spent from what the server says is left (clamped so a
 * stale/over figure can never render as negative or over 100%), and the UTC day key
 * that must match the server's reset.
 */
class UsageStatsTest {

    @Test
    fun `cap depends on the plan`() {
        assertEquals(UsageStats.PRO_DAILY_TOKENS, UsageStats.capFor("pro"))
        assertEquals(UsageStats.FREE_DAILY_TOKENS, UsageStats.capFor("free"))
        assertEquals(UsageStats.FREE_DAILY_TOKENS, UsageStats.capFor(null))
        assertEquals(UsageStats.FREE_DAILY_TOKENS, UsageStats.capFor("anything-else"))
    }

    @Test
    fun `used is cap minus remaining, clamped both ways`() {
        assertEquals(15_000, UsageStats.usedFrom(60_000, 45_000))
        assertEquals(0, UsageStats.usedFrom(60_000, 60_000))
        assertEquals(60_000, UsageStats.usedFrom(60_000, 0))
        // A remaining above the cap (shouldn't happen) can't make used negative.
        assertEquals(0, UsageStats.usedFrom(60_000, 70_000))
        // A negative remaining (shouldn't happen) can't exceed the cap.
        assertEquals(60_000, UsageStats.usedFrom(60_000, -100))
    }

    @Test
    fun `daily derives remaining and fraction`() {
        val d = UsageStats.Daily(used = 15_000, cap = 60_000)
        assertEquals(45_000, d.remaining)
        assertEquals(0.25f, d.fraction, 0.0001f)
        // No divide-by-zero when the cap is unknown.
        assertEquals(0f, UsageStats.Daily(used = 5, cap = 0).fraction, 0.0001f)
    }

    @Test
    fun `the day key is UTC, matching the server reset`() {
        assertEquals("1970-01-01", UsageStats.dayKey(0L))
        // 1_000_000_000 s = 2001-09-09T01:46:40Z
        assertEquals("2001-09-09", UsageStats.dayKey(1_000_000_000_000L))
    }

    @Test
    fun `format groups thousands the same everywhere`() {
        assertEquals("60,000", UsageStats.format(60_000))
        assertEquals("2,000,000", UsageStats.format(2_000_000))
        assertEquals("0", UsageStats.format(0))
    }
}
