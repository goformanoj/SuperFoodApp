package com.jarvis.os.voice

/** Distinct orb states from the design spec. */
enum class OrbState { Offline, Idle, Listening, Thinking, Speaking, Error }

/** UI state for the assistant, driven by speech recognition + Gemini + TTS. */
data class VoiceUiState(
    val orb: OrbState = OrbState.Idle,
    val status: String = "Starting…",
    val transcript: String = "", // what the user said (partial or final)
    val reply: String = "",      // JARVIS's spoken reply
    val amplitude: Float = 0f,   // 0..1, from mic RMS — drives the reactive orb
)
