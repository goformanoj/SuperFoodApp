package com.jarvis.os.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Thin, callback-based wrapper around Android's [SpeechRecognizer]. Deliberately
 * simple: it uses the recogniser's own default end-of-speech timing (custom
 * silence-timeout hints proved unreliable on some devices), reports events, and
 * lets the caller decide when to restart. The one extra is muting the recogniser
 * "earcon" while listening so continuous listening doesn't beep every session.
 * Must be driven from the main thread.
 */
class VoiceController(private val context: Context) {

    var onReady: () -> Unit = {}
    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onAmplitude: (Float) -> Unit = {}
    var onNoInput: () -> Unit = {}     // no-match / timeout — caller may retry
    var onFatal: (String) -> Unit = {} // e.g. permission missing

    private var recognizer: SpeechRecognizer? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var beepMuted = false
    private val earconStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
    )

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        muteEarcon()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            unmuteEarcon()
            onFatal("Could not start microphone")
        }
    }

    fun stopListening() {
        recognizer?.cancel()
        unmuteEarcon()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        unmuteEarcon()
    }

    private fun muteEarcon() {
        if (beepMuted) return
        beepMuted = true
        for (stream in earconStreams) {
            try {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            } catch (e: Exception) {
                // Some OEMs block muting certain streams (e.g. under Do Not Disturb); ignore.
            }
        }
    }

    private fun unmuteEarcon() {
        if (!beepMuted) return
        beepMuted = false
        for (stream in earconStreams) {
            try {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onReady()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) =
            onAmplitude(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                unmuteEarcon()
                onFatal("Microphone permission needed")
                return
            }
            onNoInput()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) onFinal(text) else onNoInput()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
