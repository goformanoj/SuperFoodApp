package com.jarvis.os.assistant

/** Where a single turn has got to. */
enum class TurnPhase {
    /** Nothing in flight — the microphone may open. */
    IDLE,

    /** A request is out to the model. */
    THINKING,

    /** JARVIS is talking. */
    SPEAKING,
}

/**
 * The state of the turn JARVIS is currently taking, and the one place that
 * decides when it is over.
 *
 * ## Why this type exists
 *
 * It replaces a single `busy` boolean that was doing three unrelated jobs at
 * once: gating the microphone, telling the media check that the audio it can hear
 * is JARVIS's own voice rather than the user's music, and holding off the sleep
 * timer so it could not fire mid-sentence. Those three read the same flag but do
 * not actually want the same answer, and barge-in needs to change one of them
 * without touching the others. They are named separately here — [micGated],
 * [ownVoiceOnStream], [suppressSleep] — so a future change to one is a change to
 * one.
 *
 * ## Why sequence numbers
 *
 * The boolean also had no way to tell one utterance from another, and speaking
 * uses `QUEUE_FLUSH`: a new reply cuts off the old one, and the platform then
 * reports the *old* utterance as ended after the new one has started. Treated
 * naively that late report ends the turn while JARVIS is still talking, which
 * opens the microphone, which mutes `STREAM_MUSIC` to hide the recogniser earcon
 * — and TTS plays on `STREAM_MUSIC`. The user would hear him stop dead
 * mid-answer. So every report carries the sequence it belongs to and anything
 * that is not the current one is discarded.
 *
 * ## Why interruption is synchronous
 *
 * [interrupt] moves to [TurnPhase.IDLE] immediately rather than waiting to be
 * told the speech stopped. It has to: the platform delivers a stop callback only
 * when an utterance was actually in progress, so asking TTS to stop when it is
 * already idle produces no callback at all. Anything that waits for one waits
 * forever — which is precisely the deadlock this file was written to remove.
 *
 * Pure Kotlin, no Android imports, in the manner of [WorkSession] — so all of it
 * is unit-tested without a device.
 */
class TurnState {

    var phase: TurnPhase = TurnPhase.IDLE
        private set

    /** The utterance being spoken, or [NO_SEQ] when none is. */
    var currentSeq: Long = NO_SEQ
        private set

    /**
     * The microphone must stay shut. JARVIS is either waiting on the model or
     * talking, and in both cases anything the recogniser heard would be noise or
     * his own voice.
     */
    val micGated: Boolean get() = phase != TurnPhase.IDLE

    /**
     * Sound coming out of the speaker right now is (or is about to be) JARVIS
     * himself, so the media check must not mistake it for the user's music and
     * politely yield the microphone to it.
     */
    val ownVoiceOnStream: Boolean get() = phase != TurnPhase.IDLE

    /** The idle timer must not put JARVIS to sleep in the middle of his own turn. */
    val suppressSleep: Boolean get() = phase != TurnPhase.IDLE

    /** A request has gone to the model. */
    fun think() {
        phase = TurnPhase.THINKING
        currentSeq = NO_SEQ
    }

    /** The reply is being spoken, under the sequence the speaker allocated for it. */
    fun speak(seq: Long) {
        phase = TurnPhase.SPEAKING
        currentSeq = seq
    }

    /**
     * The speaker reports [seq] has begun. Returns false when it is a stale
     * report for an utterance that has already been flushed, in which case the
     * caller must ignore it.
     */
    fun speechStarted(seq: Long): Boolean =
        phase == TurnPhase.SPEAKING && seq == currentSeq

    /**
     * The speaker reports [seq] is over.
     *
     * Returns true only when this genuinely ends the current turn — meaning the
     * caller should now carry on with whatever was waiting for JARVIS to finish
     * talking. Returns false, changing nothing, when the report is stale (a
     * flushed utterance reporting in late), a duplicate (some engines deliver
     * both a done and a stop for one utterance), or when the turn was already
     * ended by [interrupt].
     *
     * That last case is the one that makes tapping to interrupt safe: the queued
     * follow-up work must not run just because the speech it was waiting on
     * stopped, when the reason it stopped is that the user asked it to.
     *
     * A turn cut short by something outside the app — audio focus lost to a call,
     * say — does return true and does end the turn. Standing still would be worse
     * than proceeding: it is how the old code got stuck.
     */
    fun speechEnded(seq: Long, interrupted: Boolean): Boolean = end(seq)

    /**
     * The user cut JARVIS off. Ends the turn at once, without waiting for any
     * confirmation from the speaker — see the note on synchronicity above.
     *
     * Returns true if there was actually a turn in progress to end.
     */
    fun interrupt(): Boolean {
        if (phase == TurnPhase.IDLE) return false
        phase = TurnPhase.IDLE
        currentSeq = NO_SEQ
        return true
    }

    /**
     * No report ever arrived for [seq]. Returns true if that leaves a turn stuck,
     * in which case it has now been released.
     *
     * A backstop, not a mechanism: every path through the speaker promises a
     * report. This exists because "JARVIS stopped responding entirely" is the
     * worst failure this app has, and one missed callback used to cause it.
     */
    fun watchdogExpired(seq: Long): Boolean = end(seq)

    /**
     * Re-derive the phase from what the speech engine actually reports, rather
     * than from what we last remembered.
     *
     * Used when returning from a gap that callbacks could have fallen into. If
     * the engine really is still speaking the turn stands; otherwise anything in
     * flight is treated as abandoned, which is the correct reading — the work
     * that would have completed it was cancelled when we left.
     */
    fun reconcile(engineIsSpeaking: Boolean) {
        if (phase == TurnPhase.SPEAKING && engineIsSpeaking) return
        phase = TurnPhase.IDLE
        currentSeq = NO_SEQ
    }

    private fun end(seq: Long): Boolean {
        if (phase != TurnPhase.SPEAKING) return false
        if (seq != currentSeq) return false
        phase = TurnPhase.IDLE
        currentSeq = NO_SEQ
        return true
    }

    companion object {
        /** No utterance is current. */
        const val NO_SEQ = -1L
    }
}
