package com.jarvis.os.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryTest {

    @Test
    fun historyKeepsTheNewestValues() {
        var h = emptyList<Float>()
        (1..5).forEach { h = Telemetry.push(h, it.toFloat(), 3) }
        assertEquals(listOf(3f, 4f, 5f), h)
    }

    @Test
    fun gigabytesReadLikeATaskManager() {
        assertEquals("7.9 / 15.8 GB", Telemetry.gb(7_900_000_000, 15_800_000_000))
    }

    @Test
    fun uptimeClock() {
        assertEquals("00:00:00", Telemetry.clock(0))
        assertEquals("01:02:03", Telemetry.clock(3_723_000))
        assertEquals("100:00:00", Telemetry.clock(360_000_000))
        assertEquals("00:00:00", Telemetry.clock(-5))
    }

    @Test
    fun percentIsClamped() {
        assertEquals("0%", Telemetry.percent(-1f))
        assertEquals("42%", Telemetry.percent(0.42f))
        assertEquals("100%", Telemetry.percent(3f))
    }
}
