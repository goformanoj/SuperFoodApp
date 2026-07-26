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
 * Thin, callback-based wrapper around Android's [SpeechRecognizer]. It reports
 * events; the caller (AssistantEngine) decides what to do — including when to
 * restart listening. Must be driven from the main thread.
 */
class VoiceController(private val context: Context) {

    var onReady: () -> Unit = {}
    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onAmplitude: (Float) -> Unit = {}
    var onNoInput: () -> Unit = {}     // no-match / timeout / busy — caller may retry
    var onFatal: (String) -> Unit = {} // e.g. permission missing

    private var recognizer: SpeechRecognizer? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var beepMuted = false

    // The recognizer plays a start/stop "earcon" every session, and there is no
    // API flag to disable it. In wake-word mode we restart constantly, so it
    // machine-guns. Mute the streams the earcon plays on while listening, and
    // restore them the moment we stop (before JARVIS speaks — the two never
    // overlap, so its voice stays audible).
    private val earconStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
    )

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * @param snappy when true (an active conversation) end the turn quickly after
     * the user stops talking; when false (idle, waiting for the wake word) let the
     * session run on the recogniser's default longer timeouts so it stays up and
     * actually catches "Hey JARVIS" instead of restarting every second.
     */
    fun startListening(snappy: Boolean = false) {
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
            if (snappy) {
                // In-conversation: respond sooner after the user stops talking.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800)
            }
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            unmuteEarcon()
            onFatal("Could not start microphone")
        }
    }

    fun stopListening() {
        // Fully tear the recogniser down so the NEXT session starts on a fresh
        // instance. Reusing one instance across cancel/restart cycles makes many
        // devices return only partial results (never a final) on later turns —
        // i.e. it "hears" you but never responds. A new instance per turn avoids
        // that. The earcon is muted, so the extra start makes no extra noise.
        recognizer?.let {
            try {
                it.cancel()
            } catch (e: Exception) {
                // ignore
            }
            try {
                it.destroy()
            } catch (e: Exception) {
                // ignore
            }
        }
        recognizer = null
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
                // Ignore; nothing else we can safely do.
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
            // A busy/stuck recogniser can machine-gun errors. Drop it so the next
            // start builds a fresh one instead of hammering the broken instance.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT
            ) {
                try {
                    recognizer?.destroy()
                } catch (e: Exception) {
                    // ignore
                }
                recognizer = null
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
