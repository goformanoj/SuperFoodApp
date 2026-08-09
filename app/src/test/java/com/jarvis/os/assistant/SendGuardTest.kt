package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sending a message cannot be undone, so "type it" must never become "send it".
 * The cases below are taken from real device traces.
 */
class SendGuardTest {

    private val openChat = ScreenStep.Tap("Message")
    private val typeHello = ScreenStep.Type("hello")
    private val tapSend = ScreenStep.Tap("Send")

    @Test
    fun `only type hello in the chat does not send`() {
        // Verbatim from a device trace: the model appended <<TAP|Send>> and the
        // message went out even though the user said "only type".
        val steps = SendGuard.apply(
            "only type hello in the chat",
            listOf(openChat, typeHello, tapSend),
        )

        assertEquals(listOf(openChat, typeHello), steps)
    }

    @Test
    fun `type that I will be late today does not press enter`() {
        val steps = SendGuard.apply(
            "type that I will be late today",
            listOf(openChat, ScreenStep.Type("I will be late today"), ScreenStep.Enter),
        )

        assertEquals(listOf(openChat, ScreenStep.Type("I will be late today")), steps)
    }

    @Test
    fun `asking to send really does send`() {
        val steps = listOf(ScreenStep.Tap("Mom"), typeHello, tapSend)

        assertEquals(steps, SendGuard.apply("send a message to mom saying hello", steps))
    }

    @Test
    fun `type it and send it still sends`() {
        val steps = listOf(openChat, typeHello, tapSend)

        assertEquals(steps, SendGuard.apply("type hello and send it", steps))
    }

    @Test
    fun `searching is never suppressed`() {
        val search = listOf(
            ScreenStep.Open("YouTube"),
            ScreenStep.Tap("Search"),
            ScreenStep.Type("standup comedy"),
            ScreenStep.Enter,
        )

        assertEquals(search, SendGuard.apply("search YouTube for standup comedy", search))
        assertEquals(search, SendGuard.apply("type standup comedy and search", search))
        assertEquals(search, SendGuard.apply("play the first video", search))
    }

    @Test
    fun `a submit in the middle of a sequence survives`() {
        // Searching, then acting on a result — the Enter is how you get there.
        val steps = listOf(
            ScreenStep.Tap("Search"),
            ScreenStep.Type("Thriller"),
            ScreenStep.Enter,
            ScreenStep.Tap("Thriller - Michael Jackson"),
        )

        assertEquals(steps, SendGuard.apply("write Thriller in the search box", steps))
    }

    @Test
    fun `composeOnly recognises compose verbs`() {
        assertTrue(SendGuard.composeOnly("type hello"))
        assertTrue(SendGuard.composeOnly("write a message saying I am late"))
        assertTrue(SendGuard.composeOnly("draft a reply to that"))
        assertTrue(SendGuard.composeOnly("just type ok"))
    }

    @Test
    fun `composeOnly does not fire when sending is authorised`() {
        assertFalse(SendGuard.composeOnly("send hello to mom"))
        assertFalse(SendGuard.composeOnly("type hello then send it"))
        assertFalse(SendGuard.composeOnly("reply saying yes"))
        assertFalse(SendGuard.composeOnly("post that on the group"))
    }

    @Test
    fun `composeOnly stays out of the way when no compose verb was used`() {
        assertFalse(SendGuard.composeOnly("open WhatsApp"))
        assertFalse(SendGuard.composeOnly("play the first video"))
        assertFalse(SendGuard.composeOnly("what is on my calendar"))
    }

    @Test
    fun `whole words only, so typewriter is not a compose verb`() {
        assertFalse(SendGuard.composeOnly("show me the typewriter video"))
    }

    @Test
    fun `a negated send is still only composing`() {
        // "write it but don't send it yet" mentions "send", but the user forbade it:
        // this must stay compose-only so the trailing Send is dropped, not kept.
        assertTrue(SendGuard.composeOnly("write to the group but dont send it yet"))
        assertTrue(SendGuard.composeOnly("type hello but do not send it"))
        assertEquals(
            listOf(openChat, typeHello),
            SendGuard.apply("write hello but dont send it", listOf(openChat, typeHello, tapSend)),
        )
    }

    @Test
    fun `plans with nothing to strip are returned unchanged`() {
        val steps = listOf(openChat, typeHello)

        assertEquals(steps, SendGuard.apply("only type hello", steps))
        assertEquals(emptyList<ScreenStep>(), SendGuard.apply("only type hello", emptyList()))
    }
}
