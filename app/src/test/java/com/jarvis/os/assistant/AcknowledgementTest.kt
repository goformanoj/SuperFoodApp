package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The complaint these defend against is a specific one, made after using the
 * app: *"I don't like the always 'On it' answer."* Every reply that carried an
 * action said the same two syllables regardless of what it was about.
 *
 * So the tests are about variety and aboutness, not about wording.
 */
class AcknowledgementTest {

    private val openSpotify = listOf(ScreenStep.Open("Spotify"))
    private val search = listOf(
        ScreenStep.Open("Amazon Music"),
        ScreenStep.Tap("Search"),
        ScreenStep.Type("my pic playlist"),
        ScreenStep.Enter,
    )

    // --- it says what is actually happening --------------------------------

    @Test
    fun `opening an app names the app`() {
        // "Opening Spotify" is the same length as "On it" and tells the user
        // something. Taken from the plan, so it cannot name the wrong app.
        repeat(12) { turn ->
            assertTrue(
                "turn $turn did not name the app: ${Acknowledgement.forPlan(openSpotify, turn)}",
                Acknowledgement.forPlan(openSpotify, turn).contains("Spotify"),
            )
        }
    }

    @Test
    fun `searching names what is being searched for`() {
        // Repeating the words back is also the cheapest confirmation that it
        // heard them right -- this app mishears "pic" as "peak" routinely.
        repeat(12) { turn ->
            val line = Acknowledgement.forPlan(search, turn)
            assertTrue("turn $turn lost the query: $line", line.contains("my pic playlist"))
        }
    }

    @Test
    fun `a plan with nothing nameable still says something`() {
        val vague = listOf(ScreenStep.Back, ScreenStep.Enter)
        repeat(6) { turn ->
            assertTrue(Acknowledgement.forPlan(vague, turn).isNotBlank())
        }
    }

    @Test
    fun `an empty plan does not produce an empty sentence`() {
        assertTrue(Acknowledgement.forPlan(emptyList(), 0).isNotBlank())
    }

    // --- nothing is "always" anything --------------------------------------

    @Test
    fun `consecutive turns never repeat the same line`() {
        // The actual complaint, as a test.
        val lines = (0 until 6).map { Acknowledgement.forPlan(openSpotify, it) }
        lines.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("turns $i and ${i + 1} both said \"$a\"", a != b)
        }
    }

    @Test
    fun `every kind of acknowledgement offers more than one wording`() {
        fun variety(name: String, of: (Int) -> String) {
            val seen = (0 until 8).map(of).toSet()
            assertTrue("$name only ever says \"${seen.first()}\"", seen.size >= 2)
        }
        variety("forPlan") { Acknowledgement.forPlan(openSpotify, it) }
        variety("forAlarm") { Acknowledgement.forAlarm(it) }
        variety("forFile") { Acknowledgement.forFile(it) }
        variety("summoned") { Acknowledgement.summoned(it) }
        variety("farewell") { Acknowledgement.farewell(it) }
        variety("calendar add") { Acknowledgement.forCalendar(added = 1, deleted = 0, turn = it) }
    }

    @Test
    fun `a negative turn counter still picks a real line`() {
        // floorMod, not %, so a wrapped or negative count cannot crash or index
        // out of range on a device that has been running for a long time.
        assertTrue(Acknowledgement.forPlan(openSpotify, -1).isNotBlank())
        assertTrue(Acknowledgement.summoned(-7).isNotBlank())
    }

    // --- confirmations stay truthful ---------------------------------------

    @Test
    fun `rescheduling is not reported as a plain addition`() {
        repeat(6) { turn ->
            val line = Acknowledgement.forCalendar(added = 1, deleted = 1, turn = turn)
            assertFalse(
                "a reschedule should not read as only adding: $line",
                line.contains("Added to your calendar"),
            )
        }
    }

    @Test
    fun `a deletion never claims something was added`() {
        repeat(6) { turn ->
            val line = Acknowledgement.forCalendar(added = 0, deleted = 2, turn = turn)
            assertFalse("$line claims an addition", line.contains("Added"))
        }
    }

    @Test
    fun `the same turn always gives the same line`() {
        // Pure, so a trace can be replayed and the tests can pin exact strings.
        assertEquals(
            Acknowledgement.forPlan(openSpotify, 3),
            Acknowledgement.forPlan(openSpotify, 3),
        )
    }

    // --- the model's own words are never touched --------------------------

    @Test
    fun `nothing here is long enough to bury a real reply`() {
        // This is a floor for silence, not a narrator. Anything wordy would end
        // up spoken over the top of the model's own sentence.
        //
        // The bound is on the WORDS THIS FILE CONTRIBUTES, not the whole
        // sentence: a query the user actually said can be as long as they like
        // and repeating it back is the point, so counting it here would just
        // measure their phrasing rather than ours.
        val query = "bread"
        val short = listOf(
            ScreenStep.Open("Blinkit"),
            ScreenStep.Tap("Search"),
            ScreenStep.Type(query),
        )
        val all = (0 until 8).flatMap {
            listOf(
                Acknowledgement.forPlan(short, it),
                Acknowledgement.forPlan(openSpotify, it),
                Acknowledgement.forAlarm(it),
                Acknowledgement.forFile(it),
                Acknowledgement.summoned(it),
                Acknowledgement.farewell(it),
                Acknowledgement.forCalendar(1, 0, it),
            )
        }
        all.forEach {
            assertTrue("too wordy for a spoken filler: \"$it\"", it.split(" ").size <= 8)
        }
    }
}
