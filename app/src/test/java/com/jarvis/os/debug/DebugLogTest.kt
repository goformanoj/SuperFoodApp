package com.jarvis.os.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trace gets shared out of the app, so redaction is a safety property, not a
 * nicety — these tests are the guard on it.
 */
class DebugLogTest {

    @Before
    fun reset() = DebugLog.clear()

    @Test
    fun `a groq key is never written to the log`() {
        DebugLog.log(DebugLog.Stage.ERROR, "HTTP 401: invalid key gsk_abc123DEF456ghi789JKL")

        val logged = DebugLog.snapshot().single().detail
        assertFalse(logged.contains("gsk_abc123DEF456ghi789JKL"))
        assertTrue(logged.contains("***redacted***"))
    }

    @Test
    fun `a google key is never written to the log`() {
        val redacted = DebugLog.redact("key=AIzaSyA1234567890abcdefghijklmnopqrstu")

        assertFalse(redacted.contains("AIzaSyA1234567890abcdefghijklmnopqrstu"))
    }

    @Test
    fun `an authorization header is never written to the log`() {
        val redacted = DebugLog.redact("Authorization: Bearer sk-proj-abcdefghijklmnop")

        assertFalse(redacted.contains("sk-proj-abcdefghijklmnop"))
    }

    @Test
    fun `ordinary text is left alone`() {
        val text = "running Open(app=YouTube), Tap(label=Search)"

        assertEquals(text, DebugLog.redact(text))
    }

    @Test
    fun `the log is capped so a long session cannot grow without bound`() {
        repeat(DebugLog.MAX_ENTRIES + 50) { DebugLog.log(DebugLog.Stage.HEARD, "turn $it") }

        assertEquals(DebugLog.MAX_ENTRIES, DebugLog.snapshot().size)
    }

    @Test
    fun `the oldest entries are dropped first`() {
        repeat(DebugLog.MAX_ENTRIES + 1) { DebugLog.log(DebugLog.Stage.HEARD, "turn $it") }

        val details = DebugLog.snapshot().map { it.detail }
        assertFalse("the very first turn should have aged out", details.contains("turn 0"))
        assertTrue(details.contains("turn ${DebugLog.MAX_ENTRIES}"))
    }

    @Test
    fun `export includes the header and every entry`() {
        DebugLog.log(DebugLog.Stage.HEARD, "open youtube")
        DebugLog.log(DebugLog.Stage.SPOKE, "Sure, opening YouTube")

        val text = DebugLog.export(header = "OK  Microphone: Granted")

        assertTrue(text.contains("OK  Microphone: Granted"))
        assertTrue(text.contains("open youtube"))
        assertTrue(text.contains("Sure, opening YouTube"))
        assertTrue(text.contains("HEARD"))
    }

    @Test
    fun `clear empties the log`() {
        DebugLog.log(DebugLog.Stage.HEARD, "something")
        DebugLog.clear()

        assertTrue(DebugLog.snapshot().isEmpty())
    }
}
