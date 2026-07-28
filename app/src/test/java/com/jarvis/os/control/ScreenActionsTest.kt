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
    fun `a marker closed with a single angle bracket still works`() {
        // Seen on a real device: the model emitted "<<TAP|Thriller by Michael
        // Jackson>". The rigid parser both skipped the tap and leaked the raw
        // marker into the spoken reply.
        val plan = ScreenActions.parse(
            "Sure, playing Thriller. <<OPEN|YouTube> <<TAP|Thriller by Michael Jackson>",
        )

        assertEquals(
            listOf(ScreenStep.Open("YouTube"), ScreenStep.Tap("Thriller by Michael Jackson")),
            plan.steps,
        )
        assertEquals("Sure, playing Thriller.", plan.clean)
    }

    @Test
    fun `marker-looking text never reaches the spoken reply, even when unparseable`() {
        val plan = ScreenActions.parse("Playing it now. <<PLAY|something we do not support>>")

        assertFalse("protocol text must never be spoken", plan.clean.contains("<<"))
        assertEquals("Playing it now.", plan.clean)
    }

    @Test
    fun `an unrecognised marker leaves no double spaces behind`() {
        val plan = ScreenActions.parse("Opening it <<WAT|x>> for you.")

        assertEquals("Opening it for you.", plan.clean)
    }

    @Test
    fun `parses a pick, which is chosen by looking rather than by label`() {
        val plan = ScreenActions.parse(
            "<<OPEN|YouTube>> <<TAP|Search>> <<TYPE|Thriller>> <<ENTER>> <<PICK|the first video result>>",
        )

        assertEquals(
            listOf(
                ScreenStep.Open("YouTube"),
                ScreenStep.Tap("Search"),
                ScreenStep.Type("Thriller"),
                ScreenStep.Enter,
                ScreenStep.Pick("the first video result"),
            ),
            plan.steps,
        )
    }

    @Test
    fun `a pick needs the accessibility service`() {
        assertTrue(ScreenActions.parse("<<PICK|the first result>>").needsAccessibility)
    }

    @Test
    fun `a pick with no description is ignored`() {
        assertFalse(ScreenActions.parse("<<PICK|>>").hasAction)
    }

    @Test
    fun `back and home are parsed and need no argument`() {
        assertEquals(listOf(ScreenStep.Back), ScreenActions.parse("<<BACK>>").steps)
        assertEquals(listOf(ScreenStep.Home), ScreenActions.parse("<<HOME>>").steps)
    }

    @Test
    fun `back and home chain with other steps`() {
        val plan = ScreenActions.parse("Sure. <<HOME>> <<OPEN|Amazon Music>> <<TAP|Search>>")

        assertEquals(
            listOf(ScreenStep.Home, ScreenStep.Open("Amazon Music"), ScreenStep.Tap("Search")),
            plan.steps,
        )
        assertEquals("Sure.", plan.clean)
    }

    @Test
    fun `an unknown marker is not treated as a step`() {
        val plan = ScreenActions.parse("<<SWIPE|up>> <<OPEN|YouTube>>")

        assertEquals(listOf(ScreenStep.Open("YouTube")), plan.steps)
    }
}
