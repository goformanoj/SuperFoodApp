package com.jarvis.os.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each of these is a bug that has already shipped, or one the shape of the code
 * makes inevitable. The deadlock in the first test is the reason the whole type
 * exists; the stale-sequence test right after it is the reason the fix needed
 * sequence numbers rather than just an extra callback.
 */
class TurnStateTest {

    private fun speaking(seq: Long = 1L) = TurnState().apply {
        think()
        speak(seq)
    }

    // --- the deadlock ------------------------------------------------------

    @Test
    fun `an utterance that was cut off still ends the turn`() {
        // The bug this replaces: the speaker's listener overrode onDone but not
        // onStop, so stopping mid-sentence produced no callback at all, the busy
        // flag stayed set, and the microphone gate never reopened. The first
        // interruption was the last thing JARVIS ever heard.
        val turn = speaking()

        assertTrue("a cut-off utterance must still end the turn", turn.speechEnded(1L, interrupted = true))
        assertEquals(TurnPhase.IDLE, turn.phase)
        assertFalse("the mic must reopen", turn.micGated)
    }

    @Test
    fun `a turn that never reports at all is released by the watchdog`() {
        val turn = speaking()

        assertTrue(turn.watchdogExpired(1L))
        assertEquals(TurnPhase.IDLE, turn.phase)
    }

    // --- stale sequences ---------------------------------------------------

    @Test
    fun `a flushed utterance reporting late does not end the turn that replaced it`() {
        // Speaking uses QUEUE_FLUSH, so a second reply cuts off the first and the
        // platform reports the FIRST as stopped -- after the second has started.
        // Acting on that would run the follow-up work for a turn already gone and
        // open the microphone mid-sentence, which mutes STREAM_MUSIC to hide the
        // recogniser earcon. TTS plays on STREAM_MUSIC. JARVIS would go silent in
        // the middle of his own answer.
        val turn = speaking(seq = 1L)
        turn.speak(2L)

        assertFalse("the stale report must be ignored", turn.speechEnded(1L, interrupted = true))
        assertEquals(TurnPhase.SPEAKING, turn.phase)
        assertEquals(2L, turn.currentSeq)
    }

    @Test
    fun `a second report for the same utterance is ignored`() {
        // Some engines deliver both a done and a stop for one utterance.
        val turn = speaking()

        assertTrue(turn.speechEnded(1L, interrupted = false))
        assertFalse("the duplicate must not end a turn twice", turn.speechEnded(1L, interrupted = false))
    }

    @Test
    fun `speechStarted only accepts the utterance that is current`() {
        val turn = speaking(seq = 1L)
        assertTrue(turn.speechStarted(1L))

        turn.speak(2L)
        assertFalse("the flushed utterance must not report as started", turn.speechStarted(1L))
        assertTrue(turn.speechStarted(2L))
    }

    // --- interruption ------------------------------------------------------

    @Test
    fun `interrupting ends the turn without waiting to be told`() {
        // It cannot wait: asking TTS to stop when it is already idle produces no
        // callback whatsoever, so anything that waits for one waits forever.
        val turn = speaking()

        assertTrue(turn.interrupt())
        assertEquals(TurnPhase.IDLE, turn.phase)
    }

    @Test
    fun `the stop that follows an interruption does not run the queued work`() {
        // The user tapped to cut JARVIS off. The speaker will duly report the
        // utterance stopped a moment later -- and that report must NOT read as
        // "he finished, carry on", or the app switch the user just interrupted
        // would go ahead anyway after the silence.
        val turn = speaking()
        turn.interrupt()

        assertFalse(turn.speechEnded(1L, interrupted = true))
        assertEquals(TurnPhase.IDLE, turn.phase)
    }

    @Test
    fun `interrupting when nothing is in flight is a no-op`() {
        assertFalse(TurnState().interrupt())
    }

    @Test
    fun `a turn can be interrupted while still thinking`() {
        val turn = TurnState().apply { think() }

        assertTrue(turn.interrupt())
        assertEquals(TurnPhase.IDLE, turn.phase)
    }

    // --- reconciling after a gap -------------------------------------------

    @Test
    fun `a turn still being spoken survives reconciliation`() {
        val turn = speaking()

        turn.reconcile(engineIsSpeaking = true)

        assertEquals(TurnPhase.SPEAKING, turn.phase)
        assertEquals(1L, turn.currentSeq)
    }

    @Test
    fun `a turn the engine has no memory of is abandoned`() {
        val turn = speaking()

        turn.reconcile(engineIsSpeaking = false)

        assertEquals(TurnPhase.IDLE, turn.phase)
        assertEquals(TurnState.NO_SEQ, turn.currentSeq)
    }

    @Test
    fun `an abandoned think is not left hanging`() {
        // Leaving the screen cancels the pending callbacks, so a request that was
        // in flight will never complete the turn. Reconciling has to clear it or
        // the microphone stays shut for good.
        val turn = TurnState().apply { think() }

        turn.reconcile(engineIsSpeaking = false)

        assertEquals(TurnPhase.IDLE, turn.phase)
    }

    // --- the three jobs the old boolean was doing --------------------------

    @Test
    fun `the three former meanings of busy stay pinned together`() {
        // These were one flag. They are separate now so that changing one is a
        // change to one -- this test is what makes that a deliberate act rather
        // than an accident, by failing the moment they stop agreeing.
        val turn = TurnState()
        assertFalse(turn.micGated)
        assertFalse(turn.ownVoiceOnStream)
        assertFalse(turn.suppressSleep)

        turn.think()
        assertTrue("the mic must be shut while waiting on the model", turn.micGated)
        assertTrue(turn.ownVoiceOnStream)
        assertTrue("JARVIS must not fall asleep mid-turn", turn.suppressSleep)

        turn.speak(1L)
        assertTrue(turn.micGated)
        assertTrue("the speaker output is JARVIS, not the user's music", turn.ownVoiceOnStream)
        assertTrue(turn.suppressSleep)

        turn.speechEnded(1L, interrupted = false)
        assertFalse(turn.micGated)
        assertFalse(turn.ownVoiceOnStream)
        assertFalse(turn.suppressSleep)
    }

    @Test
    fun `a fresh turn state is idle`() {
        val turn = TurnState()

        assertEquals(TurnPhase.IDLE, turn.phase)
        assertEquals(TurnState.NO_SEQ, turn.currentSeq)
    }
}
