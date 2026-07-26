package com.jarvis.os.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.jarvis.os.ai.Brain
import com.jarvis.os.voice.OrbState
import com.jarvis.os.voice.Speaker
import com.jarvis.os.voice.VoiceController
import com.jarvis.os.voice.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Orchestrates the assistant loop:
 *   listen (SpeechRecognizer) -> think (Gemini) -> speak (TextToSpeech) -> listen…
 * Owns the single [VoiceUiState] the UI observes. Driven from the Activity
 * lifecycle (onMicPermission / resume / pause / destroy).
 */
class AssistantEngine(context: Context) {

    private val appContext = context.applicationContext
    private val _state = mutableStateOf(VoiceUiState(status = "Starting…"))
    val state: State<VoiceUiState> get() = _state

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val voice = VoiceController(appContext)
    private val speaker = Speaker(appContext)

    private var micGranted = false
    private var visible = false
    private var busy = false // thinking or speaking — do not listen

    init {
        voice.onReady = { if (!busy) set { it.copy(orb = OrbState.Listening, status = "Listening…") } }
        voice.onPartial = { text ->
            if (!busy) set { it.copy(orb = OrbState.Listening, status = "Listening…", transcript = text) }
        }
        voice.onAmplitude = { amp -> if (!busy) set { it.copy(amplitude = amp) } }
        voice.onNoInput = { restartSoon() }
        voice.onFatal = { msg -> set { it.copy(orb = OrbState.Error, status = msg, amplitude = 0f) } }
        voice.onFinal = { text -> handleUtterance(text) }
        speaker.onDone = { onSpokenDone() }
    }

    fun onMicPermission(granted: Boolean) {
        micGranted = granted
        if (granted) {
            if (visible && !busy) listen()
        } else {
            set { it.copy(orb = OrbState.Error, status = "Microphone permission needed", amplitude = 0f) }
        }
    }

    fun resume() {
        visible = true
        if (micGranted && !busy) listen()
    }

    fun pause() {
        visible = false
        main.removeCallbacksAndMessages(null)
        voice.stopListening()
        speaker.stop()
        set { it.copy(amplitude = 0f) }
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        voice.destroy()
        speaker.shutdown()
        scope.cancel()
    }

    private fun listen() {
        if (!voice.isAvailable()) {
            set { it.copy(orb = OrbState.Error, status = "Speech recognition unavailable") }
            return
        }
        busy = false
        set { it.copy(orb = OrbState.Listening, status = "Listening…", amplitude = 0f) }
        voice.startListening()
    }

    private fun restartSoon() {
        if (!visible || busy || !micGranted) return
        main.postDelayed({ if (visible && !busy && micGranted) voice.startListening() }, 350L)
    }

    private fun handleUtterance(userText: String) {
        busy = true
        voice.stopListening()
        main.removeCallbacksAndMessages(null)
        set {
            it.copy(orb = OrbState.Thinking, status = "Thinking…", transcript = userText, reply = "", amplitude = 0f)
        }

        if (!Brain.hasKey()) {
            val msg = "No AI key yet. Add a GROQ_API_KEY secret in GitHub to switch on my brain."
            set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = msg) }
            speaker.speak(msg)
            return
        }

        scope.launch {
            try {
                val reply = Brain.generate(userText)
                set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = reply) }
                speaker.speak(reply)
            } catch (e: Exception) {
                // Surface the real reason on screen so failures are diagnosable.
                val detail = e.message ?: e.javaClass.simpleName
                set { it.copy(orb = OrbState.Error, status = "Brain error", reply = detail) }
                main.postDelayed({ onSpokenDone() }, 3000L)
            }
        }
    }

    private fun onSpokenDone() {
        busy = false
        if (visible && micGranted) {
            listen()
        } else {
            set { it.copy(orb = OrbState.Idle, status = "Paused", amplitude = 0f) }
        }
    }

    private inline fun set(block: (VoiceUiState) -> VoiceUiState) {
        _state.value = block(_state.value)
    }
}
