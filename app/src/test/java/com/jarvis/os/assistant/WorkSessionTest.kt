package com.jarvis.os.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules from the spec, as tests. The mic-ownership ones matter most: the
 * earlier background-wake service broke the app by fighting the in-app
 * recogniser, so "exactly one owner" is the property to defend.
 */
class WorkSessionTest {

    private fun session(mic: Boolean = true, visible: Boolean = true) = WorkSession().apply {
        onMicPermission(mic)
        onVisibilityChanged(visible)
    }

    // --- mic ownership -----------------------------------------------------

    // --- barge-in: the third holder of the one microphone --------------------

    @Test
    fun `while JARVIS speaks the barge-in listener owns the mic, not the recogniser`() {
        // The recogniser cannot take this turn: startListening() mutes
        // STREAM_MUSIC to suppress its earcon, and TTS plays on STREAM_MUSIC, so
        // handing it the mic here would make JARVIS inaudible mid-sentence.
        val s = session(visible = true)
        s.onSpeaking(true)

        assertEquals(MicOwner.BARGE_IN, s.owner)
    }

    @Test
    fun `speaking outranks a work session`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onSpeaking(true)

        assertEquals(MicOwner.BARGE_IN, s.owner)
    }

    @Test
    fun `speaking backgrounded with no session gives the mic to nobody`() {
        // Android 9+ hands a background app with no microphone foreground service
        // SILENCE rather than an error, so a listener started here would read
        // zeros forever and look, in a trace, exactly like a user who said
        // nothing.
        val s = session(visible = false)
        s.onSpeaking(true)

        assertEquals(MicOwner.NONE, s.owner)
    }

    @Test
    fun `speaking still needs microphone permission`() {
        val s = session(mic = false, visible = true)
        s.onSpeaking(true)

        assertEquals(MicOwner.NONE, s.owner)
    }

    @Test
    fun `when JARVIS stops speaking the previous owner comes back unchanged`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        val before = s.owner

        s.onSpeaking(true)
        s.onSpeaking(false)

        assertEquals(before, s.owner)
        assertEquals(MicOwner.SESSION, s.owner)
    }

    @Test
    fun `barge-in beats playing media, because the voice on the speaker is his own`() {
        // The yield-to-media rule exists so JARVIS does not pause the song he was
        // asked to play. It must not fire against his own speech.
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)
        s.onSpeaking(true)

        assertEquals(MicOwner.BARGE_IN, s.owner)
        assertFalse("speaking is not yielding", s.yieldedToMedia)
    }

    @Test
    fun `with JARVIS on screen the in-app engine owns the mic`() {
        assertEquals(MicOwner.ENGINE, session(visible = true).owner)
    }

    @Test
    fun `backgrounded with no session, nobody listens`() {
        assertEquals(MicOwner.NONE, session(visible = false).owner)
    }

    @Test
    fun `backgrounded during a session, the session owns the mic`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()

        assertEquals(MicOwner.SESSION, s.owner)
    }

    @Test
    fun `an active session never steals the mic while JARVIS is on screen`() {
        val s = session(visible = true)
        s.onAppOpenedByCommand()

        // Both conditions are true at once — the engine must still win, or we
        // would have two listeners, which is exactly the old bug.
        assertTrue(s.isActive)
        assertEquals(MicOwner.ENGINE, s.owner)
    }

    @Test
    fun `without mic permission nobody owns the mic, session or not`() {
        val s = session(mic = false, visible = true)
        s.onAppOpenedByCommand()
        assertEquals(MicOwner.NONE, s.owner)

        s.onVisibilityChanged(false)
        assertEquals(MicOwner.NONE, s.owner)
    }

    @Test
    fun `the foreground service starts with the session, while still on screen`() {
        val s = session(visible = true)
        assertFalse("no session: nothing to hold up", s.needsForegroundService)

        // Must be true BEFORE backgrounding. Android 12+ refuses to start a
        // foreground service from the background, so waiting until we are
        // backgrounded would throw ForegroundServiceStartNotAllowedException.
        s.onAppOpenedByCommand()
        assertTrue("session started while on screen: start the service now", s.needsForegroundService)

        s.onVisibilityChanged(false)
        assertTrue("still needed once backgrounded", s.needsForegroundService)

        s.end()
        assertFalse("session over: no reason to hold the mic", s.needsForegroundService)
    }

    @Test
    fun `no session means no service, however the app is backgrounded`() {
        val s = session(visible = true)
        s.onVisibilityChanged(false)

        assertFalse(s.needsForegroundService)
    }

    @Test
    fun `without mic permission the service is never started`() {
        val s = session(mic = false, visible = true)
        s.onAppOpenedByCommand()

        assertFalse(s.needsForegroundService)
    }

    // --- yielding the mic to playback ---------------------------------------

    @Test
    fun `music playing stops JARVIS holding the mic, so it does not pause the song`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        assertEquals(MicOwner.SESSION, s.owner)

        s.onMediaPlaying(true)

        assertEquals(MicOwner.NONE, s.owner)
        assertTrue("the session is still alive, just quiet", s.isActive)
        assertTrue(s.yieldedToMedia)
    }

    @Test
    fun `listening resumes by itself once the audio stops`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)

        s.onMediaPlaying(false)

        assertEquals(MicOwner.SESSION, s.owner)
        assertFalse(s.yieldedToMedia)
    }

    @Test
    fun `tapping Talk beats playback for one turn`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)
        assertEquals(MicOwner.NONE, s.owner)

        s.requestTalk()
        assertEquals(MicOwner.SESSION, s.owner)
        assertFalse("not 'yielded' while actively listening", s.yieldedToMedia)

        s.clearTalkRequest()
        assertEquals("and it does not latch on", MicOwner.NONE, s.owner)
    }

    @Test
    fun `on JARVIS's own screen it still listens while media plays`() {
        // The user is deliberately looking at JARVIS — being heard is the point.
        val s = session(visible = true)
        s.onMediaPlaying(true)

        assertEquals(MicOwner.ENGINE, s.owner)
    }

    @Test
    fun `media never revives a session that was never started`() {
        val s = session(visible = false)
        s.onMediaPlaying(true)
        s.requestTalk()

        assertEquals(MicOwner.NONE, s.owner)
        assertFalse(s.yieldedToMedia)
    }

    @Test
    fun `the service keeps running while yielding, so Talk stays reachable`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)

        assertTrue(s.needsForegroundService)
    }

    // --- when a session starts ---------------------------------------------

    @Test
    fun `opening and closing JARVIS without a command never starts a session`() {
        val s = session(visible = true)
        s.onVisibilityChanged(false)
        s.onVisibilityChanged(true)
        s.onVisibilityChanged(false)

        assertFalse(s.isActive)
        assertEquals(MicOwner.NONE, s.owner)
    }

    @Test
    fun `only a command that opened an app starts a session`() {
        val s = session(visible = false)
        assertFalse(s.isActive)

        s.onAppOpenedByCommand()
        assertTrue(s.isActive)
    }

    // --- stop phrase --------------------------------------------------------

    @Test
    fun `thank you jarvis ends the session`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()

        assertTrue(s.endIfStopPhrase("thank you Jarvis"))
        assertFalse(s.isActive)
        assertEquals(MicOwner.NONE, s.owner)
    }

    @Test
    fun `the stop phrase survives punctuation, capitals and surrounding words`() {
        assertTrue(WorkSession.isStopPhrase("Thank you, Jarvis!"))
        assertTrue(WorkSession.isStopPhrase("okay thank you jarvis"))
        assertTrue(WorkSession.isStopPhrase("THANK YOU JARVIS"))
        assertTrue(WorkSession.isStopPhrase("Thanks Jarvis."))
        assertTrue(WorkSession.isStopPhrase("that's all Jarvis"))
        assertTrue(WorkSession.isStopPhrase("Goodbye Jarvis"))
    }

    @Test
    fun `ordinary commands do not end the session`() {
        assertFalse(WorkSession.isStopPhrase("open WhatsApp"))
        assertFalse(WorkSession.isStopPhrase("thank you"))
        assertFalse(WorkSession.isStopPhrase("jarvis"))
        assertFalse(WorkSession.isStopPhrase("say thank you to mom for me"))
        assertFalse(WorkSession.isStopPhrase("what's on my calendar"))
    }

    @Test
    fun `a non-stop phrase leaves the session running`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()

        assertFalse(s.endIfStopPhrase("scroll down"))
        assertTrue(s.isActive)
        assertEquals(MicOwner.SESSION, s.owner)
    }

    @Test
    fun `ending an already-ended session is harmless`() {
        val s = session(visible = false)
        s.end()
        s.end()

        assertFalse(s.isActive)
    }

    // --- the floating orb ----------------------------------------------------

    @Test
    fun `the floating orb is up while a session runs in another app`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()

        assertTrue(s.wantsBubble)
    }

    @Test
    fun `thank you Jarvis takes the floating orb away`() {
        // Reported from a device: the orb stayed on screen after the stop phrase.
        // "thank you Jarvis" ends the session, the wake word and the listening —
        // if it does not also end the orb, the one dismissal the user has does
        // not dismiss it, and it stops being a presence and becomes litter.
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        assertTrue(s.wantsBubble)

        s.endIfStopPhrase("thank you jarvis")

        assertFalse(s.wantsBubble)
    }

    @Test
    fun `the notification Stop takes it away too`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()

        s.end()

        assertFalse(s.wantsBubble)
    }

    @Test
    fun `no orb on JARVIS's own screen`() {
        // There is already a 280dp orb in the middle of it.
        val s = session(visible = true)
        s.onAppOpenedByCommand()

        assertFalse(s.wantsBubble)
    }

    @Test
    fun `no orb without a session — leaving the app is not being asked for something`() {
        val s = session(visible = false)

        assertFalse(s.wantsBubble)
    }

    @Test
    fun `the orb stays up while audio plays and the mic has stood down`() {
        // The session is still live; only the microphone yielded. Hiding the orb
        // here would remove the one way back in at exactly the moment the wake
        // word is covering the gap.
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)

        assertTrue(s.wantsBubble)
    }
}
