package com.jarvis.os.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt is not code, but it is the single most-edited string in the project
 * and every rule in it was paid for by a device failure. These tests stop a
 * future trim from quietly removing one, and stop the raw-string escape bug from
 * coming back.
 */
class SystemPromptTest {

    @Test
    fun `contains no literal backslash-n`() {
        // It is a Kotlin RAW string: \n is not an escape there, it is two
        // characters. Both clients carried this bug for the life of the Files
        // feature, sending the model the text "\n" instead of a line break.
        assertFalse(
            "a raw string cannot escape newlines — use a real line break",
            SYSTEM_PROMPT.contains("\\n"),
        )
    }

    @Test
    fun `stays within its token budget`() {
        // It rides on EVERY request. At ~2,175 tokens it was over half of Groq's
        // 12,000 tokens-per-minute allowance by itself, which is what produced 25
        // rate-limit rejections in 30 seconds on the user's device.
        // ~4 chars per token is the usual rough estimate; 6,000 chars ≈ 1,500.
        assertTrue(
            "prompt is ${SYSTEM_PROMPT.length} chars — it is charged on every request",
            SYSTEM_PROMPT.length < 6_000,
        )
    }

    @Test
    fun `every marker the app can parse is documented`() {
        for (marker in listOf("<<OPEN|", "<<TAP|", "<<TYPE|", "<<ENTER>>", "<<PICK|", "<<BACK>>", "<<HOME>>")) {
            assertTrue("$marker is parsed but not taught", SYSTEM_PROMPT.contains(marker))
        }
        for (marker in listOf("<<CAL|ADD|", "<<CAL|DEL|", "<<ALARM|SET|", "<<ALARM|TIMER|")) {
            assertTrue("$marker is parsed but not taught", SYSTEM_PROMPT.contains(marker))
        }
        for (marker in listOf("<<REMEMBER|", "<<FORGET|", "<<FILE|", "<<ENDFILE>>")) {
            assertTrue("$marker is parsed but not taught", SYSTEM_PROMPT.contains(marker))
        }
    }

    @Test
    fun `the rules earned from device failures survive`() {
        val rules = mapOf(
            "do not narrate the plan" to "here are the steps",
            "typing is not sending" to "no tap on Send",
            "never claim unverified success" to "not \"sent\"",
            "pick for targets that do not exist yet" to "does not exist yet",
            "open never depends on the screen" to "never depend on what is on screen",
            "never store secrets" to "passwords, codes or card numbers",
            "cannot generate images" to "CANNOT generate images",
            "enter does not send a message" to "does NOT send a chat message",
        )
        for ((rule, phrase) in rules) {
            assertTrue("the rule '$rule' was dropped from the prompt", SYSTEM_PROMPT.contains(phrase))
        }
    }
}
