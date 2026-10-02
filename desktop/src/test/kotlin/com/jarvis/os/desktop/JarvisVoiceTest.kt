package com.jarvis.os.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisVoiceTest {

    private fun snap(latency: Int? = 142, cpu: Int? = 47, mem: Double? = 10.2) = JarvisVoice.Snapshot(
        uplink = true, node = "superfoodapp", latencyMs = latency, plan = "pro", facts = 7, conversations = 3,
        cpuPercent = cpu, memUsedGb = mem, memTotalGb = if (mem == null) null else 16.9, uptime = "00:04:17",
    )

    @Test
    fun greetingFollowsTheHour() {
        assertEquals("Good morning", JarvisVoice.greeting(5))
        assertEquals("Good morning", JarvisVoice.greeting(11))
        assertEquals("Good afternoon", JarvisVoice.greeting(12))
        assertEquals("Good afternoon", JarvisVoice.greeting(16))
        assertEquals("Good evening", JarvisVoice.greeting(17))
        assertEquals("Good evening", JarvisVoice.greeting(2))
    }

    @Test
    fun theLogReportsOnlyWhatIsReallyKnown() {
        val lines = JarvisVoice.logLines(snap())
        assertTrue(lines.any { it.contains("142 ms") })
        assertTrue(lines.any { it.contains("7 facts indexed") })
        assertTrue(lines.any { it.contains("10.2 / 16.9 GB") })
        assertTrue(lines.any { it.contains("node=superfoodapp") })
    }

    @Test
    fun anUnknownValueIsSaidToBeUnknownNotInvented() {
        val lines = JarvisVoice.logLines(snap(latency = null, cpu = null, mem = null))
        assertTrue(lines.any { it.contains("link.latency") && it.contains("awaiting first reply") })
        assertTrue(lines.any { it.contains("sys.cpu") && it.contains("sampling") })
        assertTrue(lines.any { it.contains("sys.memory") && it.contains("sampling") })
        assertTrue("no made-up numbers when nothing was measured", lines.none { it.contains("%") })
    }

    @Test
    fun aDownLinkIsReportedDownAndOneFactIsSingular() {
        val lines = JarvisVoice.logLines(snap().copy(uplink = false, facts = 1))
        assertTrue(lines.any { it.contains("link.uplink") && it.endsWith("DOWN") })
        assertTrue(lines.any { it.contains("1 fact indexed") })
    }

    @Test
    fun logColumnsAlign() {
        // The value column starts at the same offset on every line, as in a real boot log.
        val starts = JarvisVoice.logLines(snap()).map { it.indexOf(' ', 2).let { _ -> it.substring(0, 19) } }
        assertEquals(1, starts.map { it.length }.toSet().size)
    }

    @Test
    fun everyStatusHasWordingAndTheQuickCommandsAreDistinct() {
        JarvisVoice.Status.entries.forEach { assertTrue(it.line.isNotBlank()) }
        assertEquals(JarvisVoice.QUICK.size, JarvisVoice.QUICK.map { it.label }.toSet().size)
        assertTrue(JarvisVoice.QUICK.all { it.prompt.isNotBlank() })
    }
}
