package com.jarvis.os.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real tests for the screen-command parser — these replace the throwaway Python
 * ports that used to "verify" this logic by re-implementing it in another
 * language. They run on every push via `testDebugUnitTest`.
 */
class ScreenActionsTest {

    @Test
    fun `parses a full open-tap-type-enter chain in order`() {
        val plan = ScreenActions.parse(
            "Sure, pulling that up. <<OPEN|YouTube>> <<TAP|Search>> <<TYPE|standup comedy>> <<ENTER>>",
        )

        assertEquals(
            listOf(
                ScreenStep.Open("YouTube"),
                ScreenStep.Tap("Search"),
                ScreenStep.Type("standup comedy"),
                ScreenStep.Enter,
            ),
            plan.steps,
        )
    }

    @Test
    fun `strips every marker from the spoken text`() {
        val plan = ScreenActions.parse("Opening WhatsApp and pulling up Mom. <<OPEN|WhatsApp>> <<TAP|Mom>>")

        assertEquals("Opening WhatsApp and pulling up Mom.", plan.clean)
        assertFalse("markers must never reach the speaker", plan.clean.contains("<<"))
    }

    @Test
    fun `plain conversation produces no steps and is left untouched`() {
        val chat = "The capital of France is Paris."
        val plan = ScreenActions.parse(chat)

        assertFalse(plan.hasAction)
        assertEquals(chat, plan.clean)
    }

    @Test
    fun `markers are case-insensitive`() {
        val plan = ScreenActions.parse("<<open|Spotify>> <<tap|Play>>")

        assertEquals(listOf(ScreenStep.Open("Spotify"), ScreenStep.Tap("Play")), plan.steps)
    }

    @Test
    fun `arguments are trimmed`() {
        val plan = ScreenActions.parse("<<OPEN|  YouTube  >>")

        assertEquals(listOf(ScreenStep.Open("YouTube")), plan.steps)
    }

    @Test
    fun `markers with an empty argument are ignored rather than launching nothing`() {
        val plan = ScreenActions.parse("<<OPEN|>> <<TAP|>> <<TYPE|>>")

        assertFalse(plan.hasAction)
    }

    @Test
    fun `enter needs no argument`() {
        val plan = ScreenActions.parse("<<ENTER>>")

        assertEquals(listOf(ScreenStep.Enter), plan.steps)
    }

    @Test
    fun `opening an app alone does not need the accessibility service`() {
        val plan = ScreenActions.parse("<<OPEN|YouTube>>")

        assertTrue(plan.hasAction)
        assertFalse(plan.needsAccessibility)
    }

    @Test
    fun `tapping typing or entering does need the accessibility service`() {
        assertTrue(ScreenActions.parse("<<TAP|Search>>").needsAccessibility)
        assertTrue(ScreenActions.parse("<<TYPE|hello>>").needsAccessibility)
        assertTrue(ScreenActions.parse("<<ENTER>>").needsAccessibility)
        assertTrue(ScreenActions.parse("<<OPEN|YouTube>> <<TAP|Search>>").needsAccessibility)
    }

    @Test
    fun `an unknown marker is not treated as a step`() {
        val plan = ScreenActions.parse("<<SWIPE|up>> <<OPEN|YouTube>>")

        assertEquals(listOf(ScreenStep.Open("YouTube")), plan.steps)
    }
}
