package com.jarvis.os.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TurnContextTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun givesTheDateTheZoneAndTheNextSevenDays() {
        assertEquals(
            "Current date/time: Friday, 2 October 2026, 14:15 (Asia/Kolkata). " +
                "The next seven days: Saturday 3 Oct; Sunday 4 Oct; Monday 5 Oct; Tuesday 6 Oct; Wednesday 7 Oct; Thursday 8 Oct; Friday 9 Oct.",
            TurnContext.now(ms(2026, 10, 2, 14, 15), zone),
        )
    }

    @Test
    fun theListRollsOverAMonthEnd() {
        val s = TurnContext.now(ms(2026, 10, 30, 9, 0), zone)
        assert("Sunday 1 Nov" in s) { s }
    }
}
