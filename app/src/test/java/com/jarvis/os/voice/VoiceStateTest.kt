package com.jarvis.os.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceStateTest {

    @Test
    fun `the orb has exactly the states the UI switches on`() {
        assertEquals(
            listOf("Offline", "Idle", "Listening", "Thinking", "Speaking", "Error"),
            OrbState.entries.map { it.name },
        )
    }

    @Test
    fun `a fresh ui state starts idle`() {
        val s = VoiceUiState()
        assertEquals(OrbState.Idle, s.orb)
        assertEquals("Starting…", s.status)
        assertEquals(0f, s.amplitude, 0f)
        assertEquals(emptyList<Any>(), s.messages)
    }

    @Test
    fun `copy changes only the named field`() {
        val s = VoiceUiState().copy(orb = OrbState.Listening, status = "Listening…")
        assertEquals(OrbState.Listening, s.orb)
        assertEquals("Listening…", s.status)
        // untouched fields keep their defaults
        assertEquals("", s.transcript)
    }
}
