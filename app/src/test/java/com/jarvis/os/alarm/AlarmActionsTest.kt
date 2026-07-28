package com.jarvis.os.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AlarmActionsTest {

    @Test
    fun `parses a one-off alarm`() {
        val (clean, actions) = AlarmActions.parse("Setting that now. <<ALARM|SET|07:30|Gym>>")

        assertEquals("Setting that now.", clean)
        assertEquals(AlarmAction.SetAlarm(7, 30, "Gym"), actions.single())
    }

    @Test
    fun `parses a repeating alarm`() {
        val (_, actions) = AlarmActions.parse("<<ALARM|SET|06:15|Run|MON,WED,FRI>>")

        val alarm = actions.single() as AlarmAction.SetAlarm
        assertEquals(listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY), alarm.days)
    }

    @Test
    fun `day names are case and length tolerant`() {
        val (_, actions) = AlarmActions.parse("<<ALARM|SET|06:15|Run|monday, TUESDAY ,sat>>")

        val alarm = actions.single() as AlarmAction.SetAlarm
        assertEquals(listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.SATURDAY), alarm.days)
    }

    @Test
    fun `repeated days are not duplicated`() {
        val (_, actions) = AlarmActions.parse("<<ALARM|SET|06:15|Run|MON,MON,MON>>")

        assertEquals(listOf(Calendar.MONDAY), (actions.single() as AlarmAction.SetAlarm).days)
    }

    @Test
    fun `an alarm with no label gets a sensible one`() {
        val (_, actions) = AlarmActions.parse("<<ALARM|SET|09:00>>")

        assertEquals("Alarm", (actions.single() as AlarmAction.SetAlarm).label)
    }

    @Test
    fun `parses a timer`() {
        val (clean, actions) = AlarmActions.parse("Timer going. <<ALARM|TIMER|600|Pasta>>")

        assertEquals("Timer going.", clean)
        assertEquals(AlarmAction.StartTimer(600, "Pasta"), actions.single())
    }

    @Test
    fun `an impossible time is dropped rather than set to something wrong`() {
        assertTrue(AlarmActions.parse("<<ALARM|SET|25:00|Nope>>").second.isEmpty())
        assertTrue(AlarmActions.parse("<<ALARM|SET|07:99|Nope>>").second.isEmpty())
        assertTrue(AlarmActions.parse("<<ALARM|SET|half past seven|Nope>>").second.isEmpty())
        assertTrue(AlarmActions.parse("<<ALARM|SET||Nope>>").second.isEmpty())
    }

    @Test
    fun `midnight and one minute to midnight are both valid`() {
        assertEquals(0 to 0, AlarmActions.parseTime("00:00"))
        assertEquals(23 to 59, AlarmActions.parseTime("23:59"))
    }

    @Test
    fun `a zero or negative timer is refused`() {
        assertTrue(AlarmActions.parse("<<ALARM|TIMER|0|Nope>>").second.isEmpty())
        assertTrue(AlarmActions.parse("<<ALARM|TIMER|-5|Nope>>").second.isEmpty())
        assertTrue(AlarmActions.parse("<<ALARM|TIMER|ten minutes|Nope>>").second.isEmpty())
    }

    @Test
    fun `a single closing bracket still works`() {
        val (clean, actions) = AlarmActions.parse("Done. <<ALARM|SET|07:30|Gym>")

        assertEquals("Done.", clean)
        assertEquals(1, actions.size)
    }

    @Test
    fun `several alarms in one reply are all set`() {
        val (_, actions) = AlarmActions.parse("<<ALARM|SET|07:00|Wake>> <<ALARM|SET|22:30|Bed>>")

        assertEquals(2, actions.size)
    }

    @Test
    fun `plain conversation produces nothing and is left untouched`() {
        val chat = "Your alarm is set for seven thirty."
        val (clean, actions) = AlarmActions.parse(chat)

        assertTrue(actions.isEmpty())
        assertEquals(chat, clean)
    }

    @Test
    fun `markers never reach the spoken reply`() {
        val (clean, _) = AlarmActions.parse("All set. <<ALARM|SET|07:30|Gym|MON>>")

        assertFalse(clean.contains("<<"))
    }

    @Test
    fun `parseTime rejects nonsense`() {
        assertNull(AlarmActions.parseTime("7"))
        assertNull(AlarmActions.parseTime("07:30:00"))
        assertNull(AlarmActions.parseTime(""))
    }
}
