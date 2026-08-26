package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen regressions from the on-device session of 2026-08-26.
 *
 * The trace, with the silences left in:
 *
 * ```
 * 16:07:52  HEARD   (voice) can you open the pw app and tell me if i have any classes today
 * 16:07:55  REPLY   Groq/FAST: <<OPEN|pw>>
 * 16:07:56  SCREEN  running Open(app=pw)
 * 16:08:21  HEARD   (voice) do i have classes today
 * 16:08:24  REPLY   Groq/SMART: Opening the weekly schedule now.<<TAP|View Weekly Schedule>>
 * 16:08:28  SCREEN  step 1/1 Tap(label=View Weekly Schedule)
 * 16:08:38  HEARD   (voice) you didn't reply to me
 * 16:08:38  REPLY   Groq/SMART: There are no classes scheduled for today.
 * ```
 *
 * Nothing failed. The app opened, the tap landed, and the answer — when it
 * finally came — took no time at all, because everything needed had been on the
 * screen since 16:08:28. The fault was that no code path looked at a screen once
 * an action had produced it.
 */
class DeviceTrace0826Test {

    // ---------------------------------------------------------------
    // 1. 16:07:52 — the request carried an action AND a question.
    // ---------------------------------------------------------------

    @Test
    fun `the opening request leaves a question outstanding`() {
        val said = "can you open the pw app and tell me if i have any classes today"
        assertEquals("tell me if i have any classes today", FollowUp.pendingFor(said))
    }

    @Test
    fun `the reply to it was markers only, so nothing was said at all`() {
        // <<OPEN|pw>> and not one word besides. Whatever answers the question
        // has to come from after the app opens, because there was no "before".
        val plan = ScreenActions.parse("<<OPEN|pw>>")
        assertTrue(plan.hasAction)
        assertEquals("", plan.clean.trim())
    }

    // ---------------------------------------------------------------
    // 2. 16:08:21 — a BARE question, answered with a tap and then silence.
    //    This is the half a compound-detector alone would still miss.
    // ---------------------------------------------------------------

    @Test
    fun `a bare question answered by acting is still outstanding afterwards`() {
        assertEquals("do i have classes today", FollowUp.pendingFor("do i have classes today"))
    }

    @Test
    fun `an acknowledgement is not an answer`() {
        // "Opening the weekly schedule now." was spoken BEFORE the tap. It says
        // what is about to happen and nothing about classes, so holding the
        // question past it is correct.
        val plan = ScreenActions.parse(
            "Opening the weekly schedule now.<<TAP|View Weekly Schedule>>",
        )
        assertTrue(plan.hasAction)
        assertEquals("Opening the weekly schedule now.", plan.clean.trim())
        assertEquals("do i have classes today", FollowUp.pendingFor("do i have classes today"))
    }

    // ---------------------------------------------------------------
    // 3. The other direction: an ordinary command must not gain a second
    //    sentence. This fires on every turn, so a false positive here is
    //    worse than the bug it fixes.
    // ---------------------------------------------------------------

    @Test
    fun `plain commands from the same session stay silent`() {
        assertNull(FollowUp.pendingFor("open pw"))
        assertNull(FollowUp.pendingFor("can you open the pw app"))
        assertNull(FollowUp.pendingFor("open whatsapp and send hi to mom"))
        assertNull(FollowUp.pendingFor("go back"))
    }

    @Test
    fun `a complaint is not a question to answer from the screen`() {
        // 16:08:38 "you didn't reply to me" — the reply path handles this, and
        // reading it as a screen question would answer the wrong thing.
        assertNull(FollowUp.pendingFor("you didn't reply to me"))
    }
}
