package com.jarvis.os.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * Wraps Android's [SpeechRecognizer] and exposes a Compose [State]. Listens
 * continuously by restarting after each result / recoverable error. Must be
 * driven from the main thread (Activity lifecycle callbacks).
 */
class VoiceController(private val context: Context) {

    private val _state = mutableStateOf(VoiceUiState(status = "Starting…"))
    val state: State<VoiceUiState> get() = _state

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = _state.value.copy(
                orb = OrbState.Error,
                status = "Speech recognition unavailable",
                amplitude = 0f,
            )
            return
        }
        wantListening = true
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        }
        beginListening()
    }

    fun stop() {
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        _state.value = _state.value.copy(orb = OrbState.Idle, status = "Paused", amplitude = 0f)
    }

    fun destroy() {
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    fun onPermissionDenied() {
        wantListening = false
        _state.value = _state.value.copy(
            orb = OrbState.Error,
            status = "Microphone permission needed",
            amplitude = 0f,
        )
    }

    private fun beginListening() {
        if (!wantListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        try {
            recognizer?.startListening(intent)
            _state.value = _state.value.copy(orb = OrbState.Listening, status = "Listening…")
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                orb = OrbState.Error,
                status = "Could not start microphone",
                amplitude = 0f,
            )
        }
    }

    private fun scheduleRestart() {
        if (!wantListening) return
        handler.postDelayed({
            if (wantListening) {
                recognizer?.cancel()
                beginListening()
            }
        }, 350L)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = _state.value.copy(orb = OrbState.Listening, status = "Listening…")
        }

        override fun onBeginningOfSpeech() {
            _state.value = _state.value.copy(orb = OrbState.Listening, status = "Listening…")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val amp = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _state.value = _state.value.copy(amplitude = amp)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _state.value = _state.value.copy(
                orb = OrbState.Thinking,
                status = "Processing…",
                amplitude = 0f,
            )
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wantListening = false
                _state.value = _state.value.copy(
                    orb = OrbState.Error,
                    status = "Microphone permission needed",
                    amplitude = 0f,
                )
                return
            }
            // NO_MATCH / SPEECH_TIMEOUT / BUSY etc. are normal in continuous mode.
            _state.value = _state.value.copy(amplitude = 0f)
            scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                _state.value = _state.value.copy(transcript = text)
            }
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                _state.value = _state.value.copy(
                    orb = OrbState.Listening,
                    status = "Listening…",
                    transcript = text,
                )
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
