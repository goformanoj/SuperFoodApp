package com.jarvis.os.assistant

import com.jarvis.os.voice.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rigorous Part B (continuous "work session") scenarios, complementing the unit
 * coverage in `WorkSessionTest`:
 *  - one LONG end-to-end lifecycle that walks a whole hands-free errand and asserts
 *    the single mic owner at every transition (the "long continuous task" case);
 *  - broad phrase tables for the stop phrase and the wake word, since both come off
 *    a lossy speech recogniser and must be tolerant without firing on the wrong thing.
 *
 * All pure — `WorkSession`, `WorkSession.isStopPhrase`, `WakeWord.detect` carry no
 * Android dependency, so this is exhaustive and device-free.
 */
class WorkSessionScenarioTest {

    @Test
    fun `a full hands-free errand keeps exactly one mic owner at every step`() {
        val s = WorkSession()
        s.onMicPermission(true)

        // 1) User is on JARVIS's screen, talking to it.
        s.onVisibilityChanged(true)
        assertEquals(MicOwner.ENGINE, s.owner)

        // 2) A command opens an app → the session starts while still on screen
        //    (must, so the foreground service can start before backgrounding).
        s.onAppOpenedByCommand()
        assertTrue(s.isActive)
        assertTrue("service must be started before we background", s.needsForegroundService)
        assertEquals("engine still wins while visible", MicOwner.ENGINE, s.owner)

        // 3) JARVIS goes to the background as the other app comes forward.
        s.onVisibilityChanged(false)
        assertEquals(MicOwner.SESSION, s.owner)

        // 4) The song JARVIS opened starts playing → yield the mic so we don't pause it.
        s.onMediaPlaying(true)
        assertEquals(MicOwner.NONE, s.owner)
        assertTrue(s.yieldedToMedia)

        // 5) User taps Talk in the notification → listen for one turn, beating media.
        s.requestTalk()
        assertEquals(MicOwner.SESSION, s.owner)

        // 6) That turn handled → Talk must not latch; media still playing → yield again.
        s.clearTalkRequest()
        assertEquals(MicOwner.NONE, s.owner)

        // 7) Music stops → the session resumes listening in the background.
        s.onMediaPlaying(false)
        assertEquals(MicOwner.SESSION, s.owner)

        // 8) User reopens JARVIS → engine owns the mic again.
        s.onVisibilityChanged(true)
        assertEquals(MicOwner.ENGINE, s.owner)

        // 9) "thank you Jarvis" ends the session; still visible, so engine keeps the mic.
        assertTrue(s.endIfStopPhrase("okay, thank you Jarvis!"))
        assertFalse(s.isActive)
        assertFalse(s.needsForegroundService)
        assertEquals(MicOwner.ENGINE, s.owner)
    }

    @Test
    fun `without mic permission nobody ever listens, session or not`() {
        val s = WorkSession()
        s.onMicPermission(false)
        s.onVisibilityChanged(true)
        assertEquals(MicOwner.NONE, s.owner)
        s.onAppOpenedByCommand()
        s.onVisibilityChanged(false)
        assertEquals(MicOwner.NONE, s.owner)
        assertFalse("no permission → no foreground service", s.needsForegroundService)
    }

    @Test
    fun `opening and closing JARVIS without a command never starts a session`() {
        val s = WorkSession()
        s.onMicPermission(true)
        s.onVisibilityChanged(true)
        s.onVisibilityChanged(false) // backgrounded, but no command ran
        assertFalse(s.isActive)
        assertEquals(MicOwner.NONE, s.owner)
    }

    @Test
    fun `stop phrases are recognised across recogniser noise`() {
        val stops = listOf(
            "thank you jarvis",
            "Thank you, JARVIS!",
            "okay thank you jarvis",
            "thanks jarvis",
            "Thanks, Jarvis.",
            "thank you very much jarvis",
            "that's all jarvis",
            "that will be all jarvis",
            "goodbye jarvis",
            "bye jarvis",
            "Thank you Jarvis, that was perfect",
        )
        for (phrase in stops) assertTrue("should stop on: $phrase", WorkSession.isStopPhrase(phrase))
    }

    @Test
    fun `polite words that are not the stop phrase do not end the session`() {
        val notStops = listOf(
            "thank you",
            "thanks",
            "thank you very much",
            "jarvis",
            "hey jarvis",
            "thank god jarvis was here",
            "play thank you next by ariana",
            "",
        )
        for (phrase in notStops) assertFalse("must NOT stop on: $phrase", WorkSession.isStopPhrase(phrase))
    }

    @Test
    fun `the wake word fires through fillers and mishears, and splits off the command`() {
        // matched, expected trailing command
        val wakes = mapOf(
            "hey jarvis" to "",
            "jarvis" to "",
            "ok jarvis play some music" to "play some music",
            "hey travis what's the weather" to "what's the weather",
            "hello service set a timer" to "set a timer",
            "okay jarvis" to "",
        )
        for ((text, command) in wakes) {
            val w = WakeWord.detect(text)
            assertTrue("should wake on: $text", w.matched)
            assertEquals("command split for: $text", command, w.command)
        }
    }

    @Test
    fun `the wake word does not fire mid-sentence or on unrelated speech`() {
        val notWakes = listOf(
            "thank you jarvis",     // that's the stop phrase, not a summons
            "play a song jarvis",   // name buried at the end
            "tell jarvis i said hi",
            "the weather today is nice",
            "",
        )
        for (text in notWakes) assertFalse("must NOT wake on: $text", WakeWord.isWake(text))
    }
}
