package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gemini's response shape differs from Groq's (`candidates[0].content.parts[*].text`,
 * concatenated), so its parser gets its own cover. org.json → Robolectric, same as the
 * Groq parser test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiClientParseTest {

    private val happy =
        """{"candidates":[{"content":{"parts":[{"text":"Hello "},{"text":"world"}]}}]}"""
    private val noCandidates = """{"candidates":[]}"""
    private val errorBody = """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""

    @Test
    fun `parseFirstText concatenates all parts and trims`() {
        assertEquals("Hello world", GeminiClient.parseFirstText(happy))
    }

    @Test
    fun `parseFirstText returns empty on no candidates or malformed json`() {
        assertEquals("", GeminiClient.parseFirstText(noCandidates))
        assertEquals("", GeminiClient.parseFirstText("not json at all"))
        assertEquals("", GeminiClient.parseFirstText("""{"candidates":[{"content":{}}]}"""))
    }

    @Test
    fun `extractError surfaces the error message`() {
        assertEquals("Quota exceeded", GeminiClient.extractError(errorBody))
    }

    @Test
    fun `extractError falls back to the raw body when there is no error message`() {
        assertEquals("gateway timeout", GeminiClient.extractError("gateway timeout"))
    }
}
