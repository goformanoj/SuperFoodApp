package com.jarvis.os.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Text-to-speech wrapper. [onDone] fires (on the main thread) when speech ends,
 * or immediately if TTS is unavailable. If [speak] is called before the engine
 * has finished initializing, the text is buffered and spoken once ready — this
 * matters because replies can arrive faster than TTS init completes.
 */
class Speaker(context: Context) {

    var onDone: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val res = engine.setLanguage(Locale.getDefault())
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post { this@Speaker.onDone() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { this@Speaker.onDone() }
                    }
                })
                ready = true
                pending?.let { text ->
                    pending = null
                    doSpeak(text)
                }
            } else {
                failed = true
                pending?.let {
                    pending = null
                    main.post { onDone() }
                }
            }
        }
    }

    fun speak(text: String) {
        when {
            failed -> main.post { onDone() }
            ready -> doSpeak(text)
            else -> pending = text
        }
    }

    private fun doSpeak(text: String) {
        val engine = tts
        if (engine == null) {
            main.post { onDone() }
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val UTTERANCE_ID = "jarvis"
    }
}
