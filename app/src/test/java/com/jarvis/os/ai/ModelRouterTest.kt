package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing exists to preserve the big model's per-model daily quota. It errs
 * toward SMART on purpose: a slow good answer beats a fast poor one, and the
 * saving only needs to come from the easy majority to be worth having.
 */
class ModelRouterTest {

    @Test
    fun `everyday commands go to the fast model`() {
        listOf(
            "open YouTube",
            "play Thriller",
            "set an alarm for seven",
            "send mom a message saying hello",
            "go back",
            "search for standup comedy",
            "add a meeting tomorrow at ten",
            "cancel my dentist appointment",
        ).forEach { assertEquals(it, Tier.FAST, ModelRouter.tierFor(it)) }
    }

    @Test
    fun `anything asking for thinking goes to the smart model`() {
        listOf(
            "why is the sky blue",
            "explain how a jet engine works",
            "tell me about Michael Jackson",
            "what do you think of this plan",
            "should I take the job",
            "what is the difference between RAM and storage",
        ).forEach { assertEquals(it, Tier.SMART, ModelRouter.tierFor(it)) }
    }

    @Test
    fun `a request to think wins even when it opens with a command verb`() {
        // "Tell me about" is the giveaway, not the first word.
        assertEquals(Tier.SMART, ModelRouter.tierFor("show me why the sky is blue"))
        assertEquals(Tier.SMART, ModelRouter.tierFor("find me an explain of how engines work"))
    }

    @Test
    fun `a long utterance is a conversation however it starts`() {
        val long = "open the app and then tell me everything you know about how " +
            "this thing works because I have been wondering for ages"

        assertEquals(Tier.SMART, ModelRouter.tierFor(long))
    }

    @Test
    fun `anything unfamiliar defaults to the smart model`() {
        assertEquals(Tier.SMART, ModelRouter.tierFor("hello Jarvis"))
        assertEquals(Tier.SMART, ModelRouter.tierFor("thanks"))
        assertEquals(Tier.SMART, ModelRouter.tierFor("I'm bored"))
        assertEquals(Tier.SMART, ModelRouter.tierFor(""))
    }

    @Test
    fun `punctuation and capitals do not change the decision`() {
        assertEquals(Tier.FAST, ModelRouter.tierFor("Open YouTube!"))
        assertEquals(Tier.FAST, ModelRouter.tierFor("  play  Thriller  "))
    }

    @Test
    fun `expectsAction recognises a command so a fumbled one can be retried`() {
        assertTrue(ModelRouter.expectsAction("open WhatsApp"))
        assertTrue(ModelRouter.expectsAction("set an alarm for six"))
        assertFalse(ModelRouter.expectsAction("why is the sky blue"))
        assertFalse(ModelRouter.expectsAction("hello"))
        assertFalse(ModelRouter.expectsAction(""))
    }

    @Test
    fun `the command length bound is a sentence, not a paragraph`() {
        assertTrue(ModelRouter.MAX_COMMAND_WORDS in 6..20)
    }
}
