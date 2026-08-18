package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model lists themselves, guarded.
 *
 * A device trace (2026-08-18) opens with
 *
 * ```
 * 19:22:59  ERROR  model llama-3.3-70b-versatile is retired — dropping it
 * ```
 *
 * and the account's own Groq dashboard shows the matching HTTP 404 in every
 * cluster of traffic that day. Nothing visibly broke — the fallback chain did its
 * job — which is exactly why it could have sat there indefinitely: a dead model at
 * the head of the list costs one wasted round trip per process, forever, and says
 * nothing about it except one line most traces never get read for.
 *
 * Plain JUnit, no Robolectric: these touch no JSON.
 */
class GroqModelListTest {

    @Test
    fun `no tier offers a model the provider has retired`() {
        for (tier in Tier.values()) {
            val models = GroqClient.modelsForTest(tier)
            for (dead in GroqClient.RETIRED_UPSTREAM) {
                assertFalse(
                    "$tier still offers $dead, which Groq answers with a 404",
                    models.contains(dead),
                )
            }
        }
    }

    @Test
    fun `every tier has a fallback, so one model out of quota is not a dead end`() {
        // The reason the retirement was survivable in the first place. Losing it
        // would turn a routine rate limit into a failed turn.
        for (tier in Tier.values()) {
            assertTrue(
                "$tier has no fallback model",
                GroqClient.modelsForTest(tier).size >= 2,
            )
        }
    }

    @Test
    fun `no tier lists the same model twice`() {
        for (tier in Tier.values()) {
            val models = GroqClient.modelsForTest(tier)
            assertEquals("$tier repeats a model", models.size, models.distinct().size)
        }
    }

    @Test
    fun `the fast tier leads with the small model and smart with the large one`() {
        // Quotas are per model, so the tiers exist to keep a command from spending
        // the allowance a real conversational turn will need.
        assertEquals("llama-3.1-8b-instant", GroqClient.modelsForTest(Tier.FAST).first())
        assertEquals("openai/gpt-oss-120b", GroqClient.modelsForTest(Tier.SMART).first())
    }
}
