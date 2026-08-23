package com.jarvis.os.voice

import com.jarvis.os.data.ChatTurn

/** Distinct orb states from the design spec. */
enum class OrbState { Offline, Idle, Listening, Thinking, Speaking, Error }

/**
 * UI state for the assistant, driven by speech recognition + Brain + TTS.
 *
 * **Microphone amplitude is deliberately NOT in here**, and that is the single
 * most important thing about this type. It used to be, and because the whole app
 * is composed under one `val state by engine.state` read at the top of
 * `setContent`, every RMS callback from the microphone built a new `VoiceUiState`
 * and **recomposed the entire tree** — on every screen, several times a second,
 * for the whole time the wake word was being listened for, which is nearly always.
 * That is what *"the app isn't smooth idk why"* was.
 *
 * Amplitude now lives in its own `State<Float>` on the engine and is passed down
 * as a `() -> Float` lambda, so the read happens inside the draw phase of the two
 * things that care and recomposes nothing at all.
 *
 * The rule this type now encodes: **anything that changes at sensor rate does not
 * belong in a state object that the whole app reads.**
 */
data class VoiceUiState(
    val orb: OrbState = OrbState.Idle,
    val status: String = "Starting…",
    val transcript: String = "",             // what the user said (partial or final)
    val reply: String = "",                  // JARVIS's spoken reply
    val messages: List<ChatTurn> = emptyList(), // full conversation history (for the Chat screen)
)
