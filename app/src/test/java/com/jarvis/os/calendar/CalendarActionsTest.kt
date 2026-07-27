package com.jarvis.os.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real tests for the calendar-command parser. Runs on every push. */
class CalendarActionsTest {

    @Test
    fun `parses an add with an explicit duration`() {
        val (clean, actions) = CalendarActions.parse(
            "Adding that now. <<CAL|ADD|Dentist|2026-08-01|14:30|45>>",
        )

        assertEquals("Adding that now.", clean)
        assertEquals(1, actions.size)
        val add = actions.first() as CalAction.Add
        assertEquals("Dentist", add.title)
        assertEquals(45, add.durationMin)
        assertEquals(CalendarActions.parseStart("2026-08-01", "14:30"), add.startMillis)
    }

    @Test
    fun `duration defaults to an hour when omitted`() {
        val (_, actions) = CalendarActions.parse("<<CAL|ADD|Standup|2026-08-01|09:00>>")

        assertEquals(60, (actions.first() as CalAction.Add).durationMin)
    }

    @Test
    fun `a blank title falls back to Event`() {
        val (_, actions) = CalendarActions.parse("<<CAL|ADD||2026-08-01|09:00|30>>")

        assertEquals("Event", (actions.first() as CalAction.Add).title)
    }

    @Test
    fun `parses a delete`() {
        val (clean, actions) = CalendarActions.parse("Cancelled. <<CAL|DEL|Dentist|2026-08-01|14:30>>")

        assertEquals("Cancelled.", clean)
        assertEquals(CalAction.Delete("Dentist", "2026-08-01", "14:30"), actions.single())
    }

    @Test
    fun `DELETE and REMOVE are accepted as well as DEL`() {
        assertTrue(CalendarActions.parse("<<CAL|DELETE|X|2026-08-01|10:00>>").second.single() is CalAction.Delete)
        assertTrue(CalendarActions.parse("<<CAL|REMOVE|X|2026-08-01|10:00>>").second.single() is CalAction.Delete)
    }

    @Test
    fun `a reschedule is a delete followed by an add, in that order`() {
        val (_, actions) = CalendarActions.parse(
            "Moved it to four. <<CAL|DEL|Dentist|2026-08-01|14:30>> <<CAL|ADD|Dentist|2026-08-01|16:00|45>>",
        )

        assertEquals(2, actions.size)
        assertTrue(actions[0] is CalAction.Delete)
        assertTrue(actions[1] is CalAction.Add)
    }

    @Test
    fun `plain conversation produces no actions and is left untouched`() {
        val chat = "You have nothing on tomorrow."
        val (clean, actions) = CalendarActions.parse(chat)

        assertTrue(actions.isEmpty())
        assertEquals(chat, clean)
    }

    @Test
    fun `an unparseable date is dropped rather than booked at the epoch`() {
        val (_, actions) = CalendarActions.parse("<<CAL|ADD|Dentist|next tuesday|14:30|45>>")

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `an add missing its time is ignored`() {
        val (_, actions) = CalendarActions.parse("<<CAL|ADD|Dentist|2026-08-01>>")

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `markers never reach the spoken text`() {
        val (clean, _) = CalendarActions.parse("Done. <<CAL|ADD|Gym|2026-08-02|07:00|60>>")

        assertFalse(clean.contains("<<"))
    }

    @Test
    fun `a calendar marker closed with a single angle bracket still works`() {
        val (clean, actions) = CalendarActions.parse("Added. <<CAL|ADD|Dentist|2026-08-01|14:30|45>")

        assertEquals("Added.", clean)
        assertEquals("Dentist", (actions.single() as CalAction.Add).title)
    }

    @Test
    fun `parseStart rejects nonsense`() {
        assertNull(CalendarActions.parseStart("not-a-date", "14:30"))
    }
}
