package com.jarvis.os.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The speaker must never say a marker.
 *
 * `Markers.strip` already runs on the reply, so in practice nothing reaches here
 * wearing brackets. This is the choke point on the way to the speaker, and every
 * guard in this project is placed on the assumption that one catches what an
 * earlier stage missed — a stray bracket on screen can be ignored, a spoken one
 * cannot be un-heard.
 */
class SpokenTextMarkerTest {

    @Test
    fun `a marker is never spoken`() {
        assertEquals("Opening it.", SpokenText.plain("Opening it. <<OPEN|Files>>"))
    }

    @Test
    fun `an unterminated marker is never spoken`() {
        assertEquals("Saving that.", SpokenText.plain("Saving that. <<FILE|pdf|Meeting no"))
    }

    @Test
    fun `markers and markdown are both handled in one pass`() {
        assertEquals(
            "Savings Account earns interest.",
            SpokenText.plain("**Savings Account** earns interest. <<REMEMBER|nothing>>"),
        )
    }

    @Test
    fun `nothing wearing angle brackets survives to the speaker`() {
        assertFalse(SpokenText.plain("Sure <<A>> then <<B|c>> and <<CUT").contains("<<"))
    }

    @Test
    fun `ordinary prose is not damaged`() {
        val line = "Shifting left is written a shl b in Kotlin."
        assertEquals(line, SpokenText.plain(line))
    }
}
