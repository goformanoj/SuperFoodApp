package com.jarvis.os.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

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
    // The recogniser's ranked guesses, best first. The caller gets the whole
    // n-best list — not just the top guess — so a mishearing can be recovered
    // from the alternatives ("pic" when the user said "peak").
    var onFinal: (List<String>) -> Unit = {}
    var onAmplitude: (Float) -> Unit = {}
    var onNoInput: () -> Unit = {}     // no-match / timeout — caller may retry
    var onFatal: (String) -> Unit = {} // e.g. permission missing

    /**
     * The languages to listen for, set by whoever owns this controller and read
     * fresh on each [startListening] so a change in settings takes effect on the
     * next turn. Default English until the user chooses.
     */
    var languagePrefs: LanguagePrefs = LanguagePrefs.DEFAULT

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
            // Listen in the user's PRIMARY language. Was the device default, which
            // is wrong the moment the phone's system language is not what the user
            // speaks to JARVIS in.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languagePrefs.primary.tag)
            // With a second language chosen, ask the recogniser to DETECT which of
            // the two is being spoken and switch to it. This landed in API 33; on
            // older devices the recogniser takes a single language, so the primary
            // is the best it can promise and the secondary rides on the model's own
            // understanding of whatever transcript comes back.
            if (languagePrefs.isBilingual &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                // The platform string keys, not the RecognizerIntent constants:
                // those constants landed in API 33, and the off-device gate
                // (scripts/jvmcheck) compiles this file against an older android.jar
                // that has not got them. The keys themselves are stable platform
                // contract, and this whole block only runs on API 33+ anyway.
                putExtra(EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(EXTRA_LANGUAGE_SWITCH_ALLOWED, true)
                putStringArrayListExtra(
                    EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    ArrayList(languagePrefs.tags),
                )
            }
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Ask for the near misses too, not just the single best guess — they
            // are what lets the assistant recover a mis-heard word from context.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
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
            val hypotheses = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()
            if (hypotheses.isNotEmpty()) onFinal(hypotheses) else onNoInput()
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

    private companion object {
        // How many ranked guesses to ask the recogniser for. A handful is plenty
        // to surface the real word behind a near-homophone mishearing.
        const val MAX_HYPOTHESES = 5

        // API-33 language-detection extras, spelled as their platform string keys
        // rather than the RecognizerIntent constants — see the note at the call
        // site. Values are the stable AOSP contract for these extras.
        const val EXTRA_ENABLE_LANGUAGE_DETECTION =
            "android.speech.extra.ENABLE_LANGUAGE_DETECTION"
        const val EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES =
            "android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES"
        const val EXTRA_LANGUAGE_SWITCH_ALLOWED =
            "android.speech.extra.LANGUAGE_SWITCH_ALLOWED"
    }
}
