package com.jarvis.os.assistant

import com.jarvis.os.alarm.AlarmAction
import com.jarvis.os.alarm.AlarmActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phantom-alarm scenarios, run through the real pipeline the engine uses:
 * `AlarmActions.parse(reply)` → `AlarmGuard.apply(userText, …)`. Frozen from the
 * device trace where "play Beat It" silently set a ten-minute "nap" timer — an alarm
 * nobody asked for is a real event at a real time, so the guard drops any alarm the
 * user's words were not about, and keeps the ones they were.
 */
class AlarmGuardScenarioTest {

    private fun alarms(userText: String, modelReply: String): List<AlarmAction> =
        AlarmGuard.apply(userText, AlarmActions.parse(modelReply).second)

    @Test
    fun `a timer invented during a music request is dropped`() {
        val kept = alarms("play Beat It", "Playing it now. <<ALARM|TIMER|600|nap>>")
        assertEquals(emptyList<AlarmAction>(), kept)
    }

    @Test
    fun `a genuine alarm request is kept`() {
        val kept = alarms("set an alarm for 7:30 for the gym", "Done. <<ALARM|SET|07:30|Gym>>")
        assertEquals(1, kept.size)
        assertTrue(kept.single() is AlarmAction.SetAlarm)
    }

    @Test
    fun `a timer the user actually asked for survives`() {
        val kept = alarms("wake me up in ten minutes", "Sure. <<ALARM|TIMER|600|nap>>")
        assertEquals(1, kept.size)
        assertTrue(kept.single() is AlarmAction.StartTimer)
    }

    @Test
    fun `remind-me phrasing counts as an alarm request`() {
        val kept = alarms("remind me at 6 to call mum", "Okay. <<ALARM|SET|06:00|call mum>>")
        assertEquals(1, kept.size)
    }

    @Test
    fun `an alarm invented while asking to open an app is dropped`() {
        // No time words anywhere in "open spotify" → the marker was hallucinated.
        val kept = alarms("open spotify", "Opening it. <<ALARM|TIMER|300|focus>>")
        assertEquals(emptyList<AlarmAction>(), kept)
    }
}
