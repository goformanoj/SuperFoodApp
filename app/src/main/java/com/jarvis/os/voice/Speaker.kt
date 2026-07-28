package com.jarvis.os.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.os.debug.DebugLog
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
                val res = engine.setLanguage(Locale.UK)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                selectBestVoice(engine)
                engine.setPitch(VoicePreference.PITCH)
                engine.setSpeechRate(VoicePreference.SPEECH_RATE)
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

    /**
     * The engine's default voice is usually the blandest one installed. Rank the
     * available voices and take the best — this is most of the difference between
     * "robot" and "assistant", at no cost and no added latency.
     */
    private fun selectBestVoice(engine: TextToSpeech) {
        val best = try {
            engine.voices
                ?.filter { it.name != null && it.locale != null }
                ?.maxByOrNull {
                    VoicePreference.score(
                        language = it.locale?.language.orEmpty(),
                        country = it.locale?.country.orEmpty(),
                        name = it.name.orEmpty(),
                        quality = it.quality,
                        networkRequired = it.isNetworkConnectionRequired,
                    )
                }
        } catch (e: Exception) {
            // Some engines throw when queried too early; the default voice is fine.
            null
        } ?: return

        val score = VoicePreference.score(
            language = best.locale?.language.orEmpty(),
            country = best.locale?.country.orEmpty(),
            name = best.name.orEmpty(),
            quality = best.quality,
            networkRequired = best.isNetworkConnectionRequired,
        )
        if (score == VoicePreference.REJECT) return
        engine.voice = best
        DebugLog.log(DebugLog.Stage.SPOKE, "voice: ${best.name} (${best.locale}), quality ${best.quality}")
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
