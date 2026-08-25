package com.jarvis.os.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstructionsDraftTest {

    @Test
    fun `an empty draft composes to nothing`() {
        assertEquals("", Instructions.compose(InstructionsDraft()))
    }

    @Test
    fun `choices come out in a fixed order`() {
        // Not the order the form was filled in: the same choices must always give
        // the same string, or saving without editing would look like a change.
        val a = InstructionsDraft("sir", AnswerLength.Brief, Tone.Casual, "I am in Bangalore.")
        assertEquals(
            "Call me sir.\n" +
                "Keep answers to one sentence unless I ask for detail.\n" +
                "Talk to me like a buddy.\n" +
                "I am in Bangalore.",
            Instructions.compose(a),
        )
    }

    @Test
    fun `a draft round-trips`() {
        val draft = InstructionsDraft("Manoj", AnswerLength.Full, Tone.Formal, "Assume IST.")
        assertEquals(draft, Instructions.parse(Instructions.compose(draft)))
    }

    @Test
    fun `text typed before this screen existed is never lost`() {
        // The case that matters most. Someone had real instructions in the old
        // free-text box; a parser that does not recognise them must keep them,
        // not drop them.
        val typed = "Open cloud means open claude\ntalk to me like a buddy"
        val draft = Instructions.parse(typed)
        assertEquals("", draft.callMe)
        assertNull(draft.length)
        // "talk to me like a buddy" is not the exact generated line, so it stays
        // as the user's own words rather than being claimed as a chip.
        assertNull(draft.tone)
        assertEquals(typed, draft.extra)
    }

    @Test
    fun `the generated tone line IS recognised`() {
        val draft = Instructions.parse("Talk to me like a buddy.")
        assertEquals(Tone.Casual, draft.tone)
        assertEquals("", draft.extra)
    }

    @Test
    fun `a name is read back out of its sentence`() {
        assertEquals("sir", Instructions.parse("Call me sir.").callMe)
    }

    @Test
    fun `only the first naming line is claimed`() {
        // A second one is the user's own text and stays as written.
        val draft = Instructions.parse("Call me sir.\nCall me boss.")
        assertEquals("sir", draft.callMe)
        assertEquals("Call me boss.", draft.extra)
    }

    @Test
    fun `blank lines between the users own paragraphs survive`() {
        val draft = Instructions.parse("Call me sir.\nfirst para\n\nsecond para")
        assertEquals("first para\n\nsecond para", draft.extra)
    }

    @Test
    fun `a name with surrounding space is cleaned`() {
        assertEquals("sir", Instructions.compose(InstructionsDraft(callMe = "  sir  ")).let {
            Instructions.parse(it).callMe
        })
    }
}
