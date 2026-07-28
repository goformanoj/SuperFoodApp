package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Groq 429 bodies — the difference between "wait 20s" and "wait until tomorrow". */
class RateLimitTest {

    private val perMinute = """{"error":{"message":"Rate limit reached for model `llama-3.3-70b-versatile` in organization `org_x` on requests per minute (RPM): Limit 30, Used 30, Requested 1. Please try again in 2.5s.","type":"rate_limit_exceeded"}}"""
    private val perDay = """{"error":{"message":"Rate limit reached for model `llama-3.3-70b-versatile` in organization `org_x` on tokens per day (TPD): Limit 100000, Used 100000, Requested 500. Please try again in 12m30s.","type":"tokens"}}"""

    @Test
    fun `the Retry-After header wins when present`() {
        assertEquals(42L, RateLimit.retrySeconds("42", perMinute))
    }

    @Test
    fun `seconds are read from the message`() {
        // 2.5s must round UP — returning early just earns another 429.
        assertEquals(3L, RateLimit.retrySeconds(null, perMinute))
    }

    @Test
    fun `minutes and seconds are combined`() {
        assertEquals(750L, RateLimit.retrySeconds(null, perDay))
    }

    @Test
    fun `an unparseable body falls back to a sane wait`() {
        assertEquals(RateLimit.DEFAULT_COOLDOWN_SECONDS, RateLimit.retrySeconds(null, "server busy"))
        assertEquals(RateLimit.DEFAULT_COOLDOWN_SECONDS, RateLimit.retrySeconds("nonsense", ""))
    }

    @Test
    fun `an enormous stated wait is capped`() {
        val body = """{"error":{"message":"Please try again in 20h0m0s."}}"""
        assertEquals(RateLimit.MAX_COOLDOWN_SECONDS, RateLimit.retrySeconds(null, body))
    }

    @Test
    fun `a daily quota is told apart from a burst limit`() {
        assertTrue(RateLimit.isDailyQuota(perDay))
        assertFalse(RateLimit.isDailyQuota(perMinute))
    }

    @Test
    fun `the description keeps Groq's own wording`() {
        val out = RateLimit.describe(perDay, 750)

        assertTrue("must say WHICH limit", out.contains("tokens per day"))
        assertTrue(out.contains("12m"))
    }

    @Test
    fun `a short wait reads in seconds`() {
        assertTrue(RateLimit.describe(perMinute, 3).contains("3s"))
    }

    @Test
    fun `a body with no message field still yields something useful`() {
        assertTrue(RateLimit.describe("upstream overloaded", 20).isNotEmpty())
    }

    @Test
    fun `the short summary says a daily quota is a daily quota`() {
        val out = RateLimit.shortSummary(perDay, 965)

        assertTrue("must not imply a quick retry", out.contains("quota"))
        assertTrue(out.contains("16 minutes"))
    }

    @Test
    fun `the short summary of a burst limit invites a retry`() {
        val out = RateLimit.shortSummary(perMinute, 3)

        assertTrue(out.contains("3 seconds"))
        assertFalse(out.contains("quota"))
    }

    @Test
    fun `the short summary stays short enough for the orb`() {
        assertTrue(RateLimit.shortSummary(perDay, 965).length < 90)
    }
}
