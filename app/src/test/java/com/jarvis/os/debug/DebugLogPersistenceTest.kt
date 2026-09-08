package com.jarvis.os.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole point of persisting the trace is that the labelled examples survive a
 * restart — you cannot learn from data you threw away (Part C2 / Part H Phase 0).
 * These tests run entirely off-device against a real temp directory, because
 * [DebugLog] takes a plain [java.io.File] rather than an Android context.
 */
class DebugLogPersistenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun reset() {
        DebugLog.detach()
        DebugLog.clear()
    }

    @After
    fun tearDown() {
        DebugLog.detach()
        DebugLog.clear()
    }

    @Test
    fun `entries survive a restart`() {
        val dir = temp.newFolder()

        DebugLog.attach(dir)
        DebugLog.log(DebugLog.Stage.HEARD, "open blinkit and add milk")
        DebugLog.log(DebugLog.Stage.REPLY, "<<OPEN|Blinkit>> <<TAP|Search>>")

        // Simulate a process death + relaunch: forget everything in memory, then re-attach.
        DebugLog.detach()
        DebugLog.clear()
        DebugLog.attach(dir)

        val details = DebugLog.snapshot().map { it.detail }
        assertEquals(2, details.size)
        assertTrue(details.contains("open blinkit and add milk"))
        assertTrue(details.contains("<<OPEN|Blinkit>> <<TAP|Search>>"))
    }

    @Test
    fun `a multi-line detail round-trips through disk intact`() {
        val dir = temp.newFolder()
        val screen = "App: Blinkit\nOn screen: [a] field:\"Search\"\t[Cart]\nrow\\path"

        DebugLog.attach(dir)
        DebugLog.log(DebugLog.Stage.SCREEN, screen)

        DebugLog.detach()
        DebugLog.clear()
        DebugLog.attach(dir)

        assertEquals(screen, DebugLog.snapshot().single().detail)
    }

    @Test
    fun `encode and decode are inverses for awkward text`() {
        val entry = DebugLog.Entry(1_725_000_000_000L, DebugLog.Stage.SCREEN, "line1\nline2\ttab\\slash\rcr")

        val decoded = DebugLog.decode(DebugLog.encode(entry))

        assertEquals(entry, decoded)
    }

    @Test
    fun `a malformed line is skipped, not fatal`() {
        assertEquals(null, DebugLog.decode(""))
        assertEquals(null, DebugLog.decode("not-a-record"))
        assertEquals(null, DebugLog.decode("12345\tHEARD")) // missing detail field
        assertEquals(null, DebugLog.decode("nope\tHEARD\tdetail")) // bad timestamp
        assertEquals(null, DebugLog.decode("123\tBOGUS_STAGE\tdetail")) // unknown stage
    }

    @Test
    fun `a truncated last line does not lose the earlier entries`() {
        val dir = temp.newFolder()
        DebugLog.attach(dir)
        DebugLog.log(DebugLog.Stage.HEARD, "good entry one")
        DebugLog.log(DebugLog.Stage.HEARD, "good entry two")
        DebugLog.detach()

        // A crash mid-write can leave a half-record with no newline; emulate it.
        val file = dir.listFiles()!!.single { it.isFile }
        file.appendText("999\tHEA") // partial, unterminated

        DebugLog.clear()
        DebugLog.attach(dir)

        val details = DebugLog.snapshot().map { it.detail }
        assertEquals(2, details.size)
        assertTrue(details.contains("good entry one"))
        assertTrue(details.contains("good entry two"))
    }

    @Test
    fun `the persisted trace is redacted before it hits disk`() {
        val dir = temp.newFolder()
        DebugLog.attach(dir)

        DebugLog.log(DebugLog.Stage.ERROR, "HTTP 401 gsk_abc123DEF456ghi789JKL")

        val file = dir.listFiles()!!.single { it.isFile }
        val raw = file.readText()
        assertFalse("a key must never be written to disk", raw.contains("gsk_abc123DEF456ghi789JKL"))
        assertTrue(raw.contains("***redacted***"))
    }

    @Test
    fun `the disk trace is compacted so it cannot grow without bound`() {
        val dir = temp.newFolder()
        DebugLog.attach(dir)

        repeat(DebugLog.MAX_DISK_ENTRIES + 400) { DebugLog.log(DebugLog.Stage.HEARD, "turn $it") }

        DebugLog.detach()
        val file = dir.listFiles()!!.single { it.isFile }
        val lines = file.readLines().filter { it.isNotEmpty() }
        // The cap is soft: it compacts to MAX_DISK_ENTRIES, then is allowed to drift up
        // by COMPACT_SLACK (200) before the next rewrite — so it settles near the cap, not
        // unbounded, even though far more than the cap was written.
        assertTrue("disk should be bounded near the cap, was ${lines.size}", lines.size <= DebugLog.MAX_DISK_ENTRIES + 200)

        // And it keeps the NEWEST entries, not the oldest.
        DebugLog.clear()
        DebugLog.attach(dir)
        val details = DebugLog.snapshot().map { it.detail }
        val last = DebugLog.MAX_DISK_ENTRIES + 399
        assertTrue(details.contains("turn $last"))
        assertFalse(details.contains("turn 0"))
    }

    @Test
    fun `clear empties the disk file too`() {
        val dir = temp.newFolder()
        DebugLog.attach(dir)
        DebugLog.log(DebugLog.Stage.HEARD, "something to forget")

        DebugLog.clear()

        DebugLog.detach()
        DebugLog.attach(dir)
        assertTrue(DebugLog.snapshot().isEmpty())
    }

    @Test
    fun `logging with no file attached stays purely in-memory`() {
        // No attach() — behaviour must be exactly as before persistence existed.
        DebugLog.log(DebugLog.Stage.HEARD, "in memory only")
        assertEquals(1, DebugLog.snapshot().size)
    }
}
