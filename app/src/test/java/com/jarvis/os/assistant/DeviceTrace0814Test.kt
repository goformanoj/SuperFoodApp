package com.jarvis.os.assistant

import com.jarvis.os.control.PickFilter
import com.jarvis.os.control.ScreenActions
import com.jarvis.os.control.ScreenStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen regressions from the on-device session of 2026-08-14 (realme RMX3868,
 * Android 15). Each test names the trace line that motivated it, so a future
 * change that reintroduces the behaviour fails here rather than on the phone.
 *
 * The session's headline finding was that the errand loop was deciding its first
 * move roughly one second after launching an app — before the app had drawn — and
 * every downstream symptom followed from that.
 */
class DeviceTrace0814Test {

    // ---------------------------------------------------------------
    // 1. The loop planned against a screen that had not rendered yet.
    //    16:40:36 Open(YouTube) -> 16:40:37 "choosing … from 1 on-screen options"
    // ---------------------------------------------------------------

    @Test
    fun `a screen with almost nothing on it is not worth planning against`() {
        // What YouTube looks like ~1s after launch: the splash, one control.
        val splash = "App: YouTube. On screen: [Search]"
        assertTrue(AgentLoop.looksUnrendered(splash))
    }

    @Test
    fun `a blank screen is not worth planning against`() {
        assertTrue(AgentLoop.looksUnrendered(""))
        assertTrue(AgentLoop.looksUnrendered("   "))
    }

    @Test
    fun `a remembered screen is not a live one`() {
        // describeScreen's fallback when JARVIS itself is still in front. For an
        // errand that has just launched an app, this means it is not up yet.
        val remembered =
            "JARVIS is in front, so the screen cannot be read live. The user was last in " +
                "com.google.android.youtube (4s ago) and it looked like this — assume it is " +
                "still there unless the user says otherwise: App: YouTube. On screen: " +
                "[Home] [Shorts] [Subscriptions] [Library]"
        assertTrue(AgentLoop.looksUnrendered(remembered))
    }

    @Test
    fun `a drawn screen is ready to plan against`() {
        val drawn = "App: YouTube. On screen: [Search] [Home] [Shorts] " +
            "[How it works · 1.2M views] [Another video · 800K views]"
        assertFalse(AgentLoop.looksUnrendered(drawn))
    }

    @Test
    fun `the render wait is bounded`() {
        // A wait that could not run out would hang an errand on an app that never
        // draws anything readable.
        assertTrue(AgentLoop.MAX_RENDER_WAITS in 1..10)
        assertTrue(AgentLoop.READY_ITEMS >= 2)
    }

    // ---------------------------------------------------------------
    // 2. A reflexive BACK killed three errands outright.
    //    16:37:36 / 16:38:25 / 16:39:16 "errand stuck: backing out of the app it
    //    just opened" — each on the errand's FIRST move.
    // ---------------------------------------------------------------

    @Test
    fun `backing out on arrival is worth one more question, not the end of the errand`() {
        val move = AgentLoop.parseMove("<<BACK>>", taken = listOf(ScreenStep.Open("Amazon Music")))
        assertTrue(move is AgentMove.Blocked)
        val reason = (move as AgentMove.Blocked).reason
        assertEquals(AgentLoop.JUST_ARRIVED, reason)
        assertTrue(
            "a first-move Back is a habit, not a dead end — ask once more",
            reason in AgentLoop.NUDGEABLE,
        )
    }

    @Test
    fun `repeating itself is also worth one more question`() {
        assertTrue(AgentLoop.GOING_IN_CIRCLES in AgentLoop.NUDGEABLE)
    }

    @Test
    fun `a route already known to be wrong is not worth asking again`() {
        // Re-asking after these invites the same answer, so they end the errand.
        assertFalse(AgentLoop.ALREADY_FAILED in AgentLoop.NUDGEABLE)
        assertFalse(AgentLoop.LEFT_APP in AgentLoop.NUDGEABLE)
    }

    @Test
    fun `every nudgeable reason is still unspeakable`() {
        // They are diagnostics. A nudge must not turn one into something the user
        // hears if the second attempt also fails.
        AgentLoop.NUDGEABLE.forEach {
            assertTrue("$it must stay internal", it in AgentLoop.INTERNAL_REASONS)
        }
    }

    // ---------------------------------------------------------------
    // 3. A failed launch reported the wrong reason.
    //    16:38:25 Open(app=Search) FAILED — no app named "Search" is installed
    //    16:38:25 "errand stuck: backing out of the app it just opened"
    //    16:38:25 SPOKE "I can't see what to do from this screen"
    // ---------------------------------------------------------------

    @Test
    fun `a launch that failed says so, instead of blaming the screen`() {
        val spoken = AgentLoop.couldNotOpenMessage("Search")
        assertTrue("names the app it could not find", spoken.contains("Search"))
        assertFalse(
            "the screen was never the problem — JARVIS never got to one",
            spoken.contains("this screen"),
        )
        assertTrue("hands control back", spoken.trim().endsWith("?"))
    }

    @Test
    fun `a launch failure with no app name still speaks`() {
        val spoken = AgentLoop.couldNotOpenMessage("")
        assertTrue(spoken.isNotBlank())
        assertTrue(spoken.trim().endsWith("?"))
    }

    // ---------------------------------------------------------------
    // 4. A marker at the start of a sentence left a headless reply.
    //    16:38:05 REPLY  <<TAP|Add to wishlist>> isn't the right control to add…
    //    16:38:05 SPOKE  isn't the right control to add to cart, so I'll look…
    // ---------------------------------------------------------------

    @Test
    fun `a sentence whose subject was a marker is not spoken`() {
        val reply = "<<TAP|Add to wishlist>> isn't the right control to add to cart, so I'll " +
            "look for the right one and try again. \n\nTo add brown bread to the cart, I need " +
            "to find it on the screen first."
        val plan = ScreenActions.parse(reply)
        assertFalse(
            "the headless fragment must not reach the user's ears",
            plan.clean.startsWith("isn't"),
        )
        assertTrue(
            "the real sentence survives",
            plan.clean.startsWith("To add brown bread"),
        )
        assertEquals(listOf(ScreenStep.Tap("Add to wishlist")), plan.steps)
    }

    @Test
    fun `an ordinary reply that merely opens in lower case is left alone`() {
        // The narrow guard: only a marker at the very START of the reply can leave
        // a fragment. This one is just an informal opening and must survive intact.
        val plan = ScreenActions.parse("ok, opening YouTube now. <<OPEN|YouTube>>")
        assertEquals("ok, opening YouTube now.", plan.clean)
        assertEquals(listOf(ScreenStep.Open("YouTube")), plan.steps)
    }

    @Test
    fun `a leading marker followed by a proper sentence is untouched`() {
        val plan = ScreenActions.parse("<<OPEN|YouTube>> Opening YouTube for you.")
        assertEquals("Opening YouTube for you.", plan.clean)
    }

    @Test
    fun `a leading marker with no sentence end keeps what little there is`() {
        // Dropping everything would trade a clumsy sentence for silence.
        val plan = ScreenActions.parse("<<OPEN|YouTube>> opening that now")
        assertTrue(plan.clean.isNotBlank())
    }

    @Test
    fun `a chain of stripped markers does not leave a hole in the reply`() {
        // 16:37:10 SPOKE — the reply was displayed with six blank lines through
        // the middle of it, where the marker block had been.
        val reply = "Let me add the brown bread to the cart first.\n\n" +
            "<<OPEN|Blinkit>>\n<<TAP|Search>>\n<<TYPE|brown bread>>\n<<ENTER>>\n" +
            "<<PICK|the first brown bread result>>\n<<TAP|Add to cart>>\n" +
            "Now that the bread is in the cart, I can proceed with the order. \n\n" +
            "Would you like to proceed to checkout now?"
        val clean = ScreenActions.parse(reply).clean
        assertFalse("no run of blank lines survives", clean.contains("\n\n\n"))
        assertTrue(clean.startsWith("Let me add the brown bread"))
        assertTrue(clean.endsWith("checkout now?"))
    }

    @Test
    fun `an ordinary paragraph break is preserved`() {
        val clean = ScreenActions.parse("First line.\n\nSecond line. <<OPEN|X>>").clean
        assertEquals("First line.\n\nSecond line.", clean)
    }

    // ---------------------------------------------------------------
    // 5. The playbook learned — and replayed — a complaint.
    //    16:39:33 replaying known route "you didnt do anything" (used 2x)
    //    16:39:35 learned route "you didnt do anything" -> <<OPEN|Amazon Music>>…
    // ---------------------------------------------------------------

    private val amazonMusic = listOf(
        ScreenStep.Open("Amazon Music"),
        ScreenStep.Tap("Play Playlist Peak"),
    )

    @Test
    fun `a complaint is never learned as a route`() {
        assertNull(Playbook.learn("you didn't do anything", amazonMusic))
        assertNull(Playbook.learn("that didn't work", amazonMusic))
        assertNull(Playbook.learn("nothing happened", amazonMusic))
        assertNull(Playbook.learn("you didn't add anything to the cart", amazonMusic))
    }

    @Test
    fun `a complaint never replays a route already stored on the device`() {
        // The learning bug is fixed; the poisoned entry on the user's phone is not.
        // Replaying it is what would look exactly like the bug persisting.
        val poisoned = Route(
            template = "you didnt do anything",
            markers = "<<OPEN|Amazon Music>> <<TAP|Play Playlist Peak>>",
        )
        assertNull(Playbook.match("you didn't do anything", poisoned))
    }

    @Test
    fun `a real request is still learned and still replays`() {
        // The complaint filter must not cost the feature its purpose.
        val route = Playbook.learn(
            "play Beat It on YouTube",
            listOf(
                ScreenStep.Open("YouTube"),
                ScreenStep.Tap("Search"),
                ScreenStep.Type("Beat It"),
                ScreenStep.Enter,
            ),
        )
        assertNotNull(route)
        val steps = Playbook.match("play Thriller on YouTube", route!!)
        assertNotNull("the slot still generalises", steps)
        assertTrue(steps!!.contains(ScreenStep.Type("Thriller")))
    }

    @Test
    fun `an ordinary request containing the word not is not mistaken for a complaint`() {
        assertFalse(Playbook.isComplaint("play the song I have not heard yet"))
        assertFalse(Playbook.isComplaint("open notes"))
        assertFalse(Playbook.isComplaint("search for working from home tips"))
    }

    // ---------------------------------------------------------------
    // 6. The PICK chooser answered "Clear" — the search box's clear button.
    //    16:40:50 choosing "how it works" from 17 on-screen options -> chose "Clear"
    // ---------------------------------------------------------------

    @Test
    fun `search-box chrome is kept away from the chooser`() {
        val onScreen = listOf(
            "Clear",
            "Cancel",
            "how it works",
            "How It Works - full explainer · 1.2M views",
            "Voice search",
        )
        val choices = PickFilter.preferContent(onScreen)
        assertFalse("the clear-text button is not a thing to pick", choices.contains("Clear"))
        assertFalse(choices.contains("Cancel"))
        assertFalse(choices.contains("Voice search"))
        assertTrue(choices.contains("How It Works - full explainer · 1.2M views"))
    }

    @Test
    fun `a screen of nothing but chrome still offers something to pick`() {
        val onlyChrome = listOf("Clear", "Cancel", "Home", "Search")
        assertEquals(onlyChrome, PickFilter.preferContent(onlyChrome))
    }
}
