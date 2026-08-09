package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Groq response/error parsers use org.json, which is only a throwing stub on the
 * plain JVM — so, like the JSON stores, these run under Robolectric for the real impl.
 * Captured response bodies are inlined so the parse and error paths are locked without
 * the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroqClientParseTest {

    private val happy = """{"choices":[{"message":{"role":"assistant","content":"  hello there  "}}]}"""
    private val noChoices = """{"choices":[]}"""
    private val errorBody = """{"error":{"message":"Rate limit reached","type":"tokens"}}"""

    @Test
    fun `parseContent pulls the assistant message and trims it`() {
        assertEquals("hello there", GroqClient.parseContent(happy))
    }

    @Test
    fun `parseContent returns empty on no choices or malformed json`() {
        assertEquals("", GroqClient.parseContent(noChoices))
        assertEquals("", GroqClient.parseContent("not json at all"))
        assertEquals("", GroqClient.parseContent("""{"unexpected":true}"""))
    }

    @Test
    fun `extractError surfaces the error message`() {
        assertEquals("Rate limit reached", GroqClient.extractError(errorBody))
    }

    @Test
    fun `extractError falls back to the raw body when there is no error message`() {
        assertEquals("upstream 503", GroqClient.extractError("upstream 503"))
        // Present-but-blank error object also falls back to the body.
        val blank = """{"error":{}}"""
        assertEquals(blank, GroqClient.extractError(blank))
    }

    @Test
    fun `extractError caps the message length`() {
        val long = "x".repeat(200)
        val body = """{"error":{"message":"$long"}}"""
        assertEquals(160, GroqClient.extractError(body).length)
    }
}
