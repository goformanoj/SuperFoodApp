package com.jarvis.os.voice

/** Distinct orb states from the design spec. */
enum class OrbState { Offline, Idle, Listening, Thinking, Speaking, Error }

/** UI state driven by the speech recognizer. */
data class VoiceUiState(
    val orb: OrbState = OrbState.Idle,
    val status: String = "Starting…",
    val transcript: String = "",
    val amplitude: Float = 0f, // 0..1, from mic RMS — drives the reactive orb
)
