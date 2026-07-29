package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * These tests used to assert that commands routed to the FAST model. They kept
 * asserting it for two commits after the routing was deliberately reverted,
 * which is what turned CI red: the behaviour changed and its tests did not.
 *
 * They now pin the decision that replaced it — every turn in the assistant loop
 * goes to SMART — so a future re-introduction of routing has to change a test
 * that says why it was removed.
 */
class ModelRouterTest {

    @Test
    fun `commands go to the smart model, because the small one cannot emit markers`() {
        listOf(
            "open YouTube",
            "play Thriller",
            "set an alarm for seven",
            "send mom a message saying hello",
            "go back",
            "search for standup comedy",
        ).forEach { assertEquals(it, Tier.SMART, ModelRouter.tierFor(it)) }
    }

    @Test
    fun `anything asking for thinking goes to the smart model`() {
        listOf(
            "why is the sky blue",
            "explain how a jet engine works",
            "what is the difference between RAM and storage",
        ).forEach { assertEquals(it, Tier.SMART, ModelRouter.tierFor(it)) }
    }

    @Test
    fun `conversation, silence and nonsense all go to the smart model too`() {
        assertEquals(Tier.SMART, ModelRouter.tierFor("hello Jarvis"))
        assertEquals(Tier.SMART, ModelRouter.tierFor("thanks"))
        assertEquals(Tier.SMART, ModelRouter.tierFor(""))
    }

    @Test
    fun `the fast tier still exists for the callers where it works`() {
        // <<PICK>> chooser and the Diagnostics ping pass Tier.FAST explicitly;
        // they send ~10 tokens and expect one value back.
        assertEquals(2, Tier.entries.size)
    }
}
