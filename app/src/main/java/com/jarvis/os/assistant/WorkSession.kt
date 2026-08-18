package com.jarvis.os.assistant

/** Who is allowed to hold the microphone right now. Exactly one value, always. */
enum class MicOwner {
    /** Nobody listens. */
    NONE,

    /** The in-app engine listens because JARVIS is on screen. */
    ENGINE,

    /** The engine keeps listening while backgrounded, held up by the foreground service. */
    SESSION,

    /**
     * JARVIS is talking, and a separate echo-tolerant listener is open purely to
     * hear the user cut him off.
     *
     * This is deliberately an owner rather than something running alongside one.
     * The barge-in listener holds the microphone just as really as the recogniser
     * does, and the last time two things could hold it at once the whole feature
     * had to be reverted. Putting it inside the same enum is what keeps "two
     * owners" unrepresentable instead of merely unlikely.
     */
    BARGE_IN,
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
    private var mediaPlaying = false
    private var talkRequested = false
    private var speaking = false

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
            // Above everything: while JARVIS talks, the only thing worth hearing
            // is the user cutting him off, and the ordinary recogniser cannot do
            // that job -- it mutes STREAM_MUSIC to hide its earcon, and TTS plays
            // on STREAM_MUSIC, so opening it here would make JARVIS inaudible.
            speaking && canRecordNow -> MicOwner.BARGE_IN
            // Speaking from the background with no session to hold a microphone
            // foreground service. Android 9+ hands a background app SILENCE
            // rather than an error, so a listener here would sit reading zeros
            // with nothing in the trace to say why it never triggered.
            speaking -> MicOwner.NONE
            // On JARVIS's own screen the user is deliberately talking to it, so
            // listening wins even if something is playing.
            jarvisVisible -> MicOwner.ENGINE
            // An explicit "Talk" from the notification beats media: the user has
            // just said they want to be heard right now.
            talkRequested && sessionActive -> MicOwner.SESSION
            // Holding the mic open takes audio focus, which pauses whatever is
            // playing — exactly like an incoming call. If the user asked for
            // music, letting it play IS carrying out the instruction, so JARVIS
            // gets out of the way until they tap Talk or the audio stops.
            mediaPlaying -> MicOwner.NONE
            sessionActive -> MicOwner.SESSION
            else -> MicOwner.NONE
        }

    /**
     * Whether opening a microphone right now would actually capture the room.
     *
     * On screen we are plainly foreground. Backgrounded, only a live session
     * gives us the microphone foreground service that makes recording legal --
     * see [needsForegroundService], which is what starts it.
     */
    private val canRecordNow: Boolean get() = jarvisVisible || sessionActive

    /**
     * True while JARVIS's own UI is the foreground window.
     *
     * Exposed for the floating orb, which must be up exactly when this is false.
     * It reads the same field [owner] does, so the bubble cannot disagree with the
     * microphone about where the user is looking.
     */
    val jarvisOnScreen: Boolean get() = jarvisVisible

    /** True while a session is up but yielding the microphone to playback. */
    val yieldedToMedia: Boolean
        get() = sessionActive && micGranted && mediaPlaying &&
            !jarvisVisible && !talkRequested && !speaking

    /**
     * True when the background wake word should be the one holding the microphone.
     *
     * The second clause is the fix for a real hole. A device trace (2026-08-18)
     * has JARVIS open YouTube, a video start, and then fourteen unbroken seconds
     * of `audio started — pausing listening`. During those seconds the recogniser
     * had stood down (correctly — holding the mic takes audio focus and would
     * pause the user's video) and the background wake word was ALSO off, because
     * it was gated on there being no session at all. Nothing was listening. The
     * only way back in was the notification's Talk button — a tap, in a feature
     * whose entire promise is that you can keep talking.
     *
     * [yieldedToMedia] is exactly the safe window: the session is up, JARVIS is
     * off screen, he is not speaking, Talk has not been tapped, and the recogniser
     * is therefore not listening. The wake word can hold the mic without breaking
     * the one-owner rule, and unlike the recogniser it does not take audio focus,
     * so the video keeps playing.
     */
    val wantsHotword: Boolean
        get() = micGranted && !jarvisVisible && (!sessionActive || yieldedToMedia)

    /**
     * Something is playing through the speaker. JARVIS stops listening so it does
     * not pause it — the session stays alive, and the notification offers Talk.
     */
    fun onMediaPlaying(playing: Boolean) {
        mediaPlaying = playing
        if (!playing) talkRequested = false
    }

    /**
     * JARVIS has started or stopped speaking.
     *
     * Derived rather than remembered: [com.jarvis.os.assistant.AssistantEngine]
     * sets this from the turn phase every time it applies the owner, so the two
     * cannot drift apart the way they would if each speak path had to remember
     * to announce itself.
     */
    fun onSpeaking(value: Boolean) {
        speaking = value
    }

    /** The user tapped Talk: listen for one turn even though media is playing. */
    fun requestTalk() {
        talkRequested = true
    }

    /** Called once a turn has been handled, so Talk does not latch on forever. */
    fun clearTalkRequest() {
        talkRequested = false
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
