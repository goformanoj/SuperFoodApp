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

    private fun alarms(userText: String, priorPrompt: String, modelReply: String): List<AlarmAction> =
        AlarmGuard.apply(userText, AlarmActions.parse(modelReply).second, priorPrompt)

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

    @Test
    fun `the two-turn confirm from the device trace now sets the alarm`() {
        // Turn 1 "set an alarm for tomorrow" → JARVIS asks the time. Turn 2 "6 o'clock"
        // + the model's <<ALARM|SET|06:00|Wake Up>>. Before the fix this whole list was
        // dropped ("nothing in '6 o'clock' asked for one") and no alarm was ever set.
        val kept = alarms(
            userText = "6 o'clock",
            priorPrompt = "What time would you like the alarm to go off tomorrow?",
            modelReply = "I'll set the alarm for 6 o'clock tomorrow morning. <<ALARM|SET|06:00|Wake Up>>",
        )
        assertEquals(1, kept.size)
        assertTrue(kept.single() is AlarmAction.SetAlarm)
    }

    @Test
    fun `a stray repeat marker after the alarm is set is still dropped`() {
        // The trace kept re-emitting the marker on "it is done" / "Jarvis is a done".
        // Those have no pending time question, so they must not re-fire the alarm.
        val kept = alarms(
            userText = "it is done",
            priorPrompt = "I'll set the alarm for 6 o'clock tomorrow morning.",
            modelReply = "Sending that now: <<ALARM|SET|06:00|Wake Up>>",
        )
        assertEquals(emptyList<AlarmAction>(), kept)
    }
}
