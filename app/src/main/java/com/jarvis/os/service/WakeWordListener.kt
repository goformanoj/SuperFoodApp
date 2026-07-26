package com.jarvis.os.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jarvis.os.voice.VoiceController
import com.jarvis.os.voice.WakeWord

/**
 * Continuously listens (in the background) only for the wake word. On a final
 * transcript containing "Hey JARVIS", [onWake] is called with any command that
 * followed it. Otherwise it just keeps listening. Must run on the main thread.
 */
class WakeWordListener(context: Context, private val onWake: (String?) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val voice = VoiceController(context)
    private var running = false

    init {
        voice.onFinal = { text ->
            val command = WakeWord.extractCommand(text)
            if (command != null) onWake(command) else restartSoon()
        }
        voice.onNoInput = { restartSoon() }
        voice.onFatal = { running = false }
    }

    fun start() {
        if (!voice.isAvailable()) return
        running = true
        voice.startListening()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        voice.stopListening()
    }

    fun destroy() {
        stop()
        voice.destroy()
    }

    private fun restartSoon() {
        if (!running) return
        main.postDelayed({ if (running) voice.startListening() }, 300L)
    }
}
