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
    fun `a bare time confirms an alarm JARVIS just asked the time for`() {
        // The device trace this fixes: "set an alarm for tomorrow" → JARVIS asks
        // "What time…?" → "6 o'clock". Seen alone that last turn has no alarm word,
        // so the alarm was suppressed and never set. With the question as context it
        // is honoured.
        val ask = "What time would you like the alarm to go off tomorrow?"
        assertTrue(AlarmGuard.asksForAlarm("6 o'clock", ask))
        assertTrue(AlarmGuard.asksForAlarm("6", ask))
        assertTrue(AlarmGuard.asksForAlarm("six", ask))
        assertTrue(AlarmGuard.asksForAlarm("half past six", ask))
        assertEquals(alarm, AlarmGuard.apply("6 o'clock", alarm, ask))
    }

    @Test
    fun `a bare time with no pending question is still not an alarm`() {
        // "6 o'clock" out of nowhere (or after an ordinary remark, not a question)
        // must NOT arm an alarm — that is how the trace's stray "it is done" /
        // "Jarvis is a done" turns kept re-emitting the marker.
        assertFalse(AlarmGuard.asksForAlarm("6 o'clock"))
        assertFalse(AlarmGuard.asksForAlarm("6 o'clock", "I'll set the alarm for 6 o'clock tomorrow morning."))
        assertFalse(AlarmGuard.asksForAlarm("it is done", "What time would you like the alarm?"))
        assertEquals(emptyList<AlarmAction>(), AlarmGuard.apply("6 o'clock", alarm))
    }

    @Test
    fun `context never revives a negated alarm`() {
        // Even mid follow-up, "don't set an alarm" must stay dropped.
        val ask = "What time would you like the alarm to go off?"
        assertFalse(AlarmGuard.asksForAlarm("dont set an alarm", ask))
        assertEquals(emptyList<AlarmAction>(), AlarmGuard.apply("dont set an alarm", alarm, ask))
    }

    @Test
    fun `a negated alarm request sets nothing`() {
        // The word "alarm"/"timer" is present, but the user said NOT to — a
        // hallucinated marker must be dropped, not honoured.
        assertFalse(AlarmGuard.asksForAlarm("dont set an alarm"))
        assertFalse(AlarmGuard.asksForAlarm("do not set a timer"))
        assertEquals(emptyList<AlarmAction>(), AlarmGuard.apply("dont set an alarm", timer))
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
