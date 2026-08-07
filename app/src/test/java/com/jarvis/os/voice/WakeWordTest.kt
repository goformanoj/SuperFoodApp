package com.jarvis.os.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {

    @Test
    fun `bare wake word matches with no command`() {
        val w = WakeWord.detect("Hey JARVIS")
        assertTrue(w.matched)
        assertEquals("", w.command)
    }

    @Test
    fun `wake word plus a command splits the command off`() {
        val w = WakeWord.detect("Hey JARVIS, play my peak playlist")
        assertTrue(w.matched)
        assertEquals("play my peak playlist", w.command)
    }

    @Test
    fun `the name alone with no filler still wakes`() {
        val w = WakeWord.detect("Jarvis what's the time")
        assertTrue(w.matched)
        assertEquals("what's the time", w.command)
    }

    @Test
    fun `common recogniser mishears of the name still wake`() {
        assertTrue(WakeWord.isWake("hey travis"))
        assertTrue(WakeWord.isWake("hey service turn on the lights"))
        assertTrue(WakeWord.isWake("jervis"))
        assertEquals("turn on the lights", WakeWord.detect("hey service turn on the lights").command)
    }

    @Test
    fun `ordinary speech does not wake`() {
        assertFalse(WakeWord.isWake("play my playlist called peak"))
        assertFalse(WakeWord.isWake("can you open whatsapp"))
        assertFalse(WakeWord.isWake(""))
        assertFalse(WakeWord.isWake("what's the weather like today"))
    }

    @Test
    fun `the stop phrase is not a wake`() {
        // "thank you jarvis" ends a session; the name is mid-sentence, not a summons.
        assertFalse(WakeWord.isWake("thank you jarvis"))
        assertFalse(WakeWord.isWake("okay thanks jarvis"))
    }

    @Test
    fun `a name buried mid-sentence is not treated as a wake`() {
        assertFalse(WakeWord.isWake("I was talking to travis yesterday"))
        assertFalse(WakeWord.isWake("tell me about jarvis the character"))
    }

    @Test
    fun `punctuation and casing are tolerated`() {
        val w = WakeWord.detect("  HEY, Jarvis!  Set an alarm.  ")
        assertTrue(w.matched)
        assertEquals("set an alarm", w.command)
    }
}
