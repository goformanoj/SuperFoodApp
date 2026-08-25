package com.jarvis.os.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The net under every parser: a marker that no parser claimed must never reach
 * the user, printed or spoken.
 */
class MarkersTest {

    @Test
    fun `ordinary text is untouched`() {
        val line = "Opening WhatsApp now."
        assertEquals(line, Markers.strip(line))
    }

    @Test
    fun `a marker the app does not know is removed`() {
        assertEquals("Here you go.", Markers.strip("Here you go. <<SEARCH|bank accounts>>"))
    }

    @Test
    fun `a misspelled marker is removed`() {
        // The parsers are strict on purpose, so a typo passes straight through
        // all of them. This is the only thing that catches it.
        assertEquals("Done.", Markers.strip("Done. <<TAPP|Send>>"))
    }

    @Test
    fun `a marker cut off by a token limit is removed to end of line`() {
        assertEquals("Saving that.", Markers.strip("Saving that.\n<<FILE|pdf|Meeting no"))
    }

    @Test
    fun `an unterminated marker does not eat the following line`() {
        // It runs to the end of ITS line and no further: the sentence after it is
        // real content and losing it is worse than showing a stray bracket.
        assertEquals("First.\nSecond line survives.", Markers.strip("First. <<BROKEN\nSecond line survives."))
    }

    @Test
    fun `extra closing brackets are taken with it`() {
        assertEquals("Right.", Markers.strip("Right. <<OPEN|Files>>>"))
    }

    @Test
    fun `a marker in the middle does not leave a double space`() {
        assertEquals("Open it and read.", Markers.strip("Open it <<TAP|thing>> and read."))
    }

    @Test
    fun `several markers go together`() {
        assertEquals(
            "On it.",
            Markers.strip("On it. <<OPEN|Files>> <<TAP|a.pdf>> <<REMEMBER|nothing>>"),
        )
    }

    @Test
    fun `a reply that is only a marker becomes empty`() {
        assertEquals("", Markers.strip("<<OPEN|Files>>"))
    }

    @Test
    fun `comparison operators in ordinary prose survive`() {
        // The guard against over-reach. "a << b" is not a marker, and removing
        // real words is worse than leaving a symbol.
        val line = "In Kotlin, shifting left is written a shl b."
        assertEquals(line, Markers.strip(line))
    }

    @Test
    fun `nothing wearing angle brackets is ever left behind`() {
        val messy = "Sure. <<FILE|pdf|X>> text <<WEIRD>> more <<CUT"
        assertFalse(Markers.strip(messy).contains("<<"))
    }
}
