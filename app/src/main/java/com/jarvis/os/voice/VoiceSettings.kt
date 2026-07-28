package com.jarvis.os.voice

import android.content.Context

/**
 * Remembers which voice the user picked, so the choice survives restarts.
 *
 * Ranking the installed voices automatically is only half the job: on a fresh
 * phone the best available voice may still be poor, and nobody should have to go
 * digging through Android's accessibility settings to fix that. The picker lives
 * in JARVIS; this is where its answer is kept.
 */
class VoiceSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE)

    /** The engine voice name the user chose, or null to let JARVIS rank them. */
    var chosenVoice: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_VOICE) else putString(KEY_VOICE, value)
            }.apply()
        }

    /** Set once the install prompt has been shown, so it is never nagged twice. */
    var offeredVoiceDownload: Boolean
        get() = prefs.getBoolean(KEY_OFFERED, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFERED, value).apply()

    private companion object {
        const val KEY_VOICE = "voice_name"
        const val KEY_OFFERED = "offered_download"
    }
}
