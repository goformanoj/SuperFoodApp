package com.jarvis.os.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user asked for this directly: an alarm that is set but cannot be heard is
 * not set in any way that matters. The alarm stream has its own volume, and a
 * silenced phone accepts the alarm without complaint.
 */
class AlarmVolumeTest {

    @Test
    fun `a silent alarm stream is reported`() {
        assertEquals(AlarmVolume.Verdict.Silent, AlarmVolume.check(0, 7))
        assertNotNull(AlarmVolume.warning(AlarmVolume.check(0, 7)))
    }

    @Test
    fun `a quiet alarm stream is reported with its level`() {
        val verdict = AlarmVolume.check(1, 7)
        assertTrue(verdict is AlarmVolume.Verdict.Low)
        assertEquals(14, (verdict as AlarmVolume.Verdict.Low).percent)
        assertNotNull(AlarmVolume.warning(verdict))
    }

    @Test
    fun `a healthy volume says nothing`() {
        // Warning about a perfectly usable volume every time would train the user
        // to ignore the warning.
        assertEquals(AlarmVolume.Verdict.Fine, AlarmVolume.check(7, 7))
        assertEquals(AlarmVolume.Verdict.Fine, AlarmVolume.check(4, 7))
        assertNull(AlarmVolume.warning(AlarmVolume.check(7, 7)))
    }

    @Test
    fun `a device reporting no alarm stream is treated as silent`() {
        assertEquals(AlarmVolume.Verdict.Silent, AlarmVolume.check(3, 0))
        assertEquals(AlarmVolume.Verdict.Silent, AlarmVolume.check(0, 0))
    }

    @Test
    fun `a volume outside the range does not produce nonsense`() {
        assertEquals(AlarmVolume.Verdict.Fine, AlarmVolume.check(99, 7))
        assertEquals(AlarmVolume.Verdict.Silent, AlarmVolume.check(-3, 7))
    }

    @Test
    fun `raising targets the maximum, or the share asked for`() {
        assertEquals(7, AlarmVolume.targetFor(7))
        assertEquals(4, AlarmVolume.targetFor(7, 0.5f))
        // Never zero: "turn it up" that silences the alarm would be the opposite
        // of what was asked.
        assertEquals(1, AlarmVolume.targetFor(7, 0f))
        assertEquals(0, AlarmVolume.targetFor(0))
    }

    @Test
    fun `a request to raise the alarm volume is recognised`() {
        listOf(
            "turn the alarm volume up",
            "make the alarm louder",
            "set the alarm volume to max",
            "turn it up for the timer sound",
        ).forEach { assertTrue("'$it'", AlarmVolume.asksToRaise(it)) }
    }

    @Test
    fun `an unrelated volume request is left alone`() {
        // Turning the media volume up while a song plays must not touch the alarm.
        assertFalse(AlarmVolume.asksToRaise("turn the volume up"))
        assertFalse(AlarmVolume.asksToRaise("play it louder"))
        assertFalse(AlarmVolume.asksToRaise("set an alarm for seven"))
    }
}
