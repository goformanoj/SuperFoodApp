package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * From the device traces: "play Beat It" took eleven turns and four failed plans
 * to reach a playing song, and the sequence that finally worked was discarded.
 */
class PlaybookTest {

    private val playSteps = listOf(
        ScreenStep.Open("YouTube"),
        ScreenStep.Tap("Search"),
        ScreenStep.Type("Beat It"),
        ScreenStep.Enter,
        ScreenStep.Pick("the first Beat It video"),
    )

    @Test
    fun `a route learned from one song replays for another`() {
        // The whole point. A playbook that only matched the identical sentence
        // would never fire, because nobody asks for the same song twice.
        val route = Playbook.learn("play Beat It on YouTube", playSteps)
        assertNotNull(route)
        assertEquals("play * on youtube", route!!.template)

        val steps = Playbook.match("play Thriller on YouTube", route)
        assertNotNull(steps)
        assertEquals(
            listOf(
                ScreenStep.Open("YouTube"),
                ScreenStep.Tap("Search"),
                ScreenStep.Type("Thriller"),
                ScreenStep.Enter,
                // The PICK description is templated too, which is what you want:
                // asking for Thriller should pick the first Thriller video, not
                // hunt for the song the route was learned from.
                ScreenStep.Pick("the first Thriller video"),
            ),
            steps,
        )
    }

    @Test
    fun `a different request does not match`() {
        val route = Playbook.learn("play Beat It on YouTube", playSteps)!!
        assertNull(Playbook.match("open WhatsApp", route))
        assertNull(Playbook.match("what is the capital of France", route))
        assertNull(Playbook.match("play Beat It on Amazon Music", route))
    }

    @Test
    fun `an empty slot does not match`() {
        val route = Playbook.learn("play Beat It on YouTube", playSteps)!!
        assertNull(Playbook.match("play on youtube", route))
        assertNull(Playbook.match("play  on youtube", route))
    }

    @Test
    fun `routes that send, buy or call are never learned`() {
        // The safety position: replaying happens WITHOUT asking the model, on the
        // strength of a fuzzy match. Opening an app is recoverable; sending a
        // message to the wrong person is not.
        listOf("Send", "Place order", "Pay", "Call", "Delete").forEach { label ->
            val steps = listOf(
                ScreenStep.Open("WhatsApp"),
                ScreenStep.Tap("Mom"),
                ScreenStep.Type("hello"),
                ScreenStep.Tap(label),
            )
            assertNull("a route ending in '$label' must not be remembered", Playbook.learn("message mom", steps))
        }
    }

    @Test
    fun `a slot value that is itself an instruction is refused`() {
        // "play * on youtube" must not swallow a request that smuggles an
        // irreversible action into the slot.
        val route = Playbook.learn("play Beat It on YouTube", playSteps)!!
        assertNull(Playbook.match("play this and send it to mum on youtube", route))
    }

    @Test
    fun `a sequence with nothing typed is remembered literally`() {
        val steps = listOf(ScreenStep.Open("WhatsApp"), ScreenStep.Tap("Mom"))
        val route = Playbook.learn("open the Mom chat", steps)!!

        assertFalse("no typed text means no slot", route.template.contains(Playbook.SLOT))
        assertNotNull(Playbook.match("open the Mom chat", route))
        assertNull(Playbook.match("open the Dad chat", route))
    }

    @Test
    fun `typed text the user never said does not become a slot`() {
        val steps = listOf(
            ScreenStep.Open("YouTube"),
            ScreenStep.Tap("Search"),
            ScreenStep.Type("lofi beats to study to"),
        )
        val route = Playbook.learn("put some music on", steps)!!
        assertFalse(route.template.contains(Playbook.SLOT))
    }

    @Test
    fun `trivial and tiny requests are not worth remembering`() {
        assertNull("one step is cheap to replan", Playbook.learn("open YouTube", listOf(ScreenStep.Open("YouTube"))))
        assertNull(Playbook.learn("go", listOf(ScreenStep.Back, ScreenStep.Home)))
    }

    @Test
    fun `matching ignores case, punctuation and spacing`() {
        val route = Playbook.learn("play Beat It on YouTube", playSteps)!!
        assertNotNull(Playbook.match("Play  Thriller   on YouTube!", route))
        assertNotNull(Playbook.match("play thriller on youtube", route))
    }

    @Test
    fun `markers round-trip through the parser the model's output uses`() {
        val markers = Playbook.markersOf(playSteps)
        assertEquals(
            "<<OPEN|YouTube>> <<TAP|Search>> <<TYPE|Beat It>> <<ENTER>> <<PICK|the first Beat It video>>",
            markers,
        )
        val route = Playbook.learn("play Beat It on YouTube", playSteps)!!
        assertEquals(playSteps, Playbook.match("play Beat It on YouTube", route))
    }

    @Test
    fun `back and home survive a round trip`() {
        val steps = listOf(ScreenStep.Home, ScreenStep.Open("Amazon Music"), ScreenStep.Back)
        val route = Playbook.learn("reset and open amazon music", steps)!!
        assertEquals(steps, Playbook.match("reset and open amazon music", route))
    }

    @Test
    fun `irreversible phrases are recognised whole-word only`() {
        assertTrue(Playbook.isIrreversible("Send"))
        assertTrue(Playbook.isIrreversible("Place order"))
        assertTrue(Playbook.isIrreversible("confirm and pay"))
        // "sender" and "recall" merely contain those letters.
        assertFalse(Playbook.isIrreversible("Sender name"))
        assertFalse(Playbook.isIrreversible("Recall"))
        assertFalse(Playbook.isIrreversible("Search"))
    }
}
