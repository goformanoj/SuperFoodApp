package com.jarvis.os.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text-to-speech wrapper. [onDone] fires (on the main thread) when speech ends. */
class Speaker(context: Context) {

    var onDone: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post { this@Speaker.onDone() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { this@Speaker.onDone() }
                    }
                })
            }
        }
    }

    fun speak(text: String) {
        val engine = tts
        if (!ready || engine == null) {
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
