package com.jarvis.os.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(emptyList<Any>(), s.messages)
    }

    @Test
    fun `the mic level is not part of the shared ui state`() {
        // It was, and because the whole app composes under one read of this
        // object, every RMS callback from the microphone recomposed the entire
        // tree — including a list being scrolled on another screen. Anything
        // that changes at sensor rate has to live somewhere the app does not
        // read wholesale. This test is here so it cannot quietly come back.
        assertTrue(
            "amplitude is back on VoiceUiState",
            VoiceUiState::class.java.declaredFields.none { it.name == "amplitude" },
        )
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
