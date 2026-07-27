package com.jarvis.os.assistant

/** Who is allowed to hold the microphone right now. Exactly one value, always. */
enum class MicOwner {
    /** Nobody listens. */
    NONE,

    /** The in-app engine listens because JARVIS is on screen. */
    ENGINE,

    /** The engine keeps listening while backgrounded, held up by the foreground service. */
    SESSION,
}

/**
 * The "work session" rule: after JARVIS opens an app *because you asked it to*,
 * it keeps listening for follow-up commands while you are in that app, and stops
 * when you say "thank you Jarvis".
 *
 * The earlier background-wake service broke everything by fighting the in-app
 * recogniser for the microphone. The fix here is structural rather than
 * careful: [owner] is a single computed value, so "two owners at once" is not a
 * state this type can represent. Both the engine and the service ask this one
 * question and obey the answer.
 *
 * Pure Kotlin on purpose — no Android imports — so the whole rule is unit-tested
 * without a device.
 */
class WorkSession {

    private var micGranted = false
    private var jarvisVisible = false
    private var sessionActive = false

    /** True once an app-opening command has started a session and it hasn't been ended. */
    val isActive: Boolean get() = sessionActive

    /**
     * The single source of truth for who may listen. Note the ordering: while
     * JARVIS is on screen the in-app engine always wins, so a session can never
     * produce a second listener.
     */
    val owner: MicOwner
        get() = when {
            !micGranted -> MicOwner.NONE
            jarvisVisible -> MicOwner.ENGINE
            sessionActive -> MicOwner.SESSION
            else -> MicOwner.NONE
        }

    /**
     * The service runs for the whole session, not just the backgrounded part of
     * it. That is deliberate: Android 12+ throws
     * `ForegroundServiceStartNotAllowedException` if you try to start a foreground
     * service once you are already in the background, so it has to be started
     * while JARVIS is still on screen — which is exactly when the session begins,
     * just before it launches the other app.
     */
    val needsForegroundService: Boolean get() = sessionActive && micGranted

    fun onMicPermission(granted: Boolean) {
        micGranted = granted
    }

    fun onVisibilityChanged(visible: Boolean) {
        jarvisVisible = visible
    }

    /**
     * Called only when a command actually opened an app. Merely opening and
     * closing JARVIS must never start a session — that is the difference between
     * "listening because you asked for something" and "always listening".
     */
    fun onAppOpenedByCommand() {
        sessionActive = true
    }

    /** Ends the session (stop phrase, notification action, or the user giving up). */
    fun end() {
        sessionActive = false
    }

    /**
     * Ends the session if [text] is the stop phrase. Returns true when it did, so
     * the caller can acknowledge and skip the round trip to the model.
     */
    fun endIfStopPhrase(text: String): Boolean {
        if (!isStopPhrase(text)) return false
        end()
        return true
    }

    companion object {
        private val STOP_PHRASES = listOf(
            "thank you jarvis",
            "thanks jarvis",
            "thank you very much jarvis",
            "thats all jarvis",
            "that will be all jarvis",
            "goodbye jarvis",
            "bye jarvis",
        )

        /**
         * Tolerant of punctuation, capitalisation and surrounding words, because
         * this arrives from speech recognition — "Okay, thank you Jarvis!" must
         * stop the session just as "thank you jarvis" does.
         */
        fun isStopPhrase(text: String): Boolean {
            val normalised = text.lowercase()
                // Drop apostrophes rather than turning them into spaces, so
                // "that's all" normalises to "thats all" and not "that s all".
                .replace("'", "")
                .replace("’", "")
                .replace(Regex("[^a-z ]"), " ")
                .replace(Regex(" +"), " ")
                .trim()
            return STOP_PHRASES.any { normalised == it || normalised.contains(it) }
        }
    }
}
