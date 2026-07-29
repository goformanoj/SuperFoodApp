package com.jarvis.os.assistant

import com.jarvis.os.alarm.AlarmAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Straight from a device trace: "play Beat It" set a ten-minute timer called
 * "nap". An alarm nobody asked for is a real noise at a real time, and the user
 * finds out about it when it goes off.
 */
class AlarmGuardTest {

    private val timer = listOf<AlarmAction>(AlarmAction.StartTimer(600, "nap"))
    private val alarm = listOf<AlarmAction>(AlarmAction.SetAlarm(7, 30, "gym"))

    @Test
    fun `the trace's phantom timer is dropped`() {
        assertEquals(emptyList<AlarmAction>(), AlarmGuard.apply("play Beat It", timer))
    }

    @Test
    fun `unrelated commands never set alarms`() {
        listOf(
            "play Beat It",
            "open WhatsApp",
            "send mom a message saying hello",
            "make a PDF of the important points",
            "what is the capital of France",
            "search for standup comedy",
        ).forEach {
            assertEquals("'$it' must not set an alarm", emptyList<AlarmAction>(), AlarmGuard.apply(it, timer))
            assertFalse(AlarmGuard.asksForAlarm(it))
        }
    }

    @Test
    fun `a real alarm request is honoured`() {
        listOf(
            "set an alarm for seven",
            "wake me up at half past six",
            "set a timer for ten minutes",
            "put a timer on for the pasta",
            "remind me at 7:30",
            "alarm for tomorrow morning",
        ).forEach {
            assertTrue("'$it' asks for an alarm", AlarmGuard.asksForAlarm(it))
            assertEquals(alarm, AlarmGuard.apply(it, alarm))
        }
    }

    @Test
    fun `a bare time answers the follow-up question`() {
        // JARVIS asks "what time?" and the user answers with the time alone —
        // that turn is where the alarm actually gets set.
        assertTrue(AlarmGuard.asksForAlarm("at 7"))
        assertTrue(AlarmGuard.asksForAlarm("7:30"))
        assertTrue(AlarmGuard.asksForAlarm("in ten minutes"))
    }

    @Test
    fun `an empty list stays empty and is not an error`() {
        assertEquals(emptyList<AlarmAction>(), AlarmGuard.apply("play Beat It", emptyList()))
        assertEquals(emptyList<AlarmAction>(), AlarmGuard.apply("", emptyList()))
    }

    @Test
    fun `nothing survives when the request was not about time`() {
        // All or nothing: a reply that invented one alarm out of nowhere has not
        // earned the benefit of the doubt on a second.
        val two = timer + alarm
        assertEquals(emptyList<AlarmAction>(), AlarmGuard.apply("play Beat It", two))
        assertEquals(two, AlarmGuard.apply("set an alarm for seven", two))
    }
}
