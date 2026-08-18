package com.jarvis.os.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who covers the microphone while a work session has stood down for playback.
 *
 * From a device trace (2026-08-18): JARVIS opened YouTube on command, a video
 * started, and the trace reads
 *
 * ```
 * 19:39:04  audio started — pausing listening so it can play
 * 19:39:18  audio stopped — listening again
 * ```
 *
 * Fourteen seconds in which the recogniser had correctly stood down — holding the
 * microphone takes audio focus and would have paused the user's video — and the
 * background wake word was off too, because it was gated on there being no
 * session at all. Nothing was listening. In a feature whose whole promise is
 * "keep talking to me while you use the app", the only way back in was tapping a
 * notification.
 *
 * These tests pin the new rule AND the one it must not break: never two owners.
 */
class HotwordOwnershipTest {

    private fun session(mic: Boolean = true, visible: Boolean = true) = WorkSession().apply {
        onMicPermission(mic)
        onVisibilityChanged(visible)
    }

    // --- the hole this closes ------------------------------------------------

    @Test
    fun `a session yielded to media hands the microphone to the wake word`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)

        // The recogniser is deliberately not listening...
        assertEquals(MicOwner.NONE, s.owner)
        // ...so the wake word may hold the mic instead of nobody holding it.
        assertTrue(s.wantsHotword)
    }

    @Test
    fun `the wake word stands down the moment the audio stops`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)
        assertTrue(s.wantsHotword)

        s.onMediaPlaying(false)

        // The recogniser takes the microphone straight back.
        assertEquals(MicOwner.SESSION, s.owner)
        assertFalse(s.wantsHotword)
    }

    // --- exactly one owner, still ---------------------------------------------

    @Test
    fun `never both — whenever the recogniser listens the wake word is off`() {
        // The property that had to be reverted last time it was broken. Walked
        // over every combination rather than the few that came to mind.
        for (visible in listOf(false, true)) {
            for (inSession in listOf(false, true)) {
                for (media in listOf(false, true)) {
                    for (speaking in listOf(false, true)) {
                        val s = session(visible = visible)
                        if (inSession) s.onAppOpenedByCommand()
                        s.onMediaPlaying(media)
                        s.onSpeaking(speaking)

                        val listening = s.owner == MicOwner.ENGINE ||
                            s.owner == MicOwner.SESSION ||
                            s.owner == MicOwner.BARGE_IN
                        assertFalse(
                            "two mic owners: owner=${s.owner} visible=$visible session=$inSession " +
                                "media=$media speaking=$speaking",
                            listening && s.wantsHotword,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `on screen the wake word never runs`() {
        // The engine owns the mic on JARVIS's own screen, media or not.
        val s = session(visible = true)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)

        assertEquals(MicOwner.ENGINE, s.owner)
        assertFalse(s.wantsHotword)
    }

    @Test
    fun `while JARVIS speaks mid-session the wake word stays off`() {
        // Barge-in owns the microphone then, and it is a listener like any other.
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)
        s.onSpeaking(true)

        assertEquals(MicOwner.BARGE_IN, s.owner)
        assertFalse(s.wantsHotword)
    }

    @Test
    fun `tapping Talk takes the microphone back from the wake word`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)
        assertTrue(s.wantsHotword)

        s.requestTalk()

        assertEquals(MicOwner.SESSION, s.owner)
        assertFalse(s.wantsHotword)
    }

    @Test
    fun `no microphone permission means no wake word either`() {
        val s = session(mic = false, visible = false)
        s.onAppOpenedByCommand()
        s.onMediaPlaying(true)

        assertFalse(s.wantsHotword)
    }

    // --- the behaviour that already worked, unchanged --------------------------

    @Test
    fun `backgrounded with no session still runs the wake word as before`() {
        val s = session(visible = false)

        assertEquals(MicOwner.NONE, s.owner)
        assertTrue(s.wantsHotword)
    }

    @Test
    fun `a session that is actively listening keeps the wake word off`() {
        val s = session(visible = false)
        s.onAppOpenedByCommand()

        assertEquals(MicOwner.SESSION, s.owner)
        assertFalse(s.wantsHotword)
    }
}
