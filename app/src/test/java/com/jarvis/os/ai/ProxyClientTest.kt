package com.jarvis.os.ai

import com.jarvis.os.data.ChatTurn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Worker client's pure request/response logic. org.json → Robolectric, same as
 * the Groq/Gemini parser tests. The network itself is not exercised here (device
 * only); this pins the payload shape and the reply/error parsing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProxyClientTest {

    private val messages = listOf(
        ChatTurn(ChatTurn.USER, "open blinkit"),
    )

    @Test
    fun `payload carries messages, and context and system only when present`() {
        val json = ProxyClient.buildPayload(messages, context = "On screen: Home", systemOverride = "SYS")
        val o = JSONObject(json)
        assertEquals(1, o.getJSONArray("messages").length())
        assertEquals("user", o.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("open blinkit", o.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("On screen: Home", o.getString("context"))
        assertEquals("SYS", o.getString("system"))
    }

    @Test
    fun `no system key is sent for the ordinary assistant turn`() {
        // systemOverride == null means "use the Worker's server-side default prompt".
        val o = JSONObject(ProxyClient.buildPayload(messages, context = "", systemOverride = null))
        assertFalse(o.has("system"))
        assertFalse(o.has("context")) // blank context is omitted too
    }

    @Test
    fun `parseReply reads and trims the reply`() {
        assertEquals("Done.", ProxyClient.parseReply("""{"reply":"  Done.  ","model":"x"}"""))
    }

    @Test
    fun `parseReply is blank on missing field or junk`() {
        assertEquals("", ProxyClient.parseReply("""{"model":"x"}"""))
        assertEquals("", ProxyClient.parseReply("not json"))
    }

    @Test
    fun `error messages are safe and never leak internals`() {
        assertTrue(ProxyClient.errorMessage(401, "").contains("verify", ignoreCase = true))
        assertTrue(ProxyClient.errorMessage(403, "").contains("authoris", ignoreCase = true))
        // 429 surfaces the Worker's own spoken message when it gives one.
        assertEquals(
            "You've used today's free turns.",
            ProxyClient.errorMessage(429, """{"error":"over_cap","message":"You've used today's free turns."}"""),
        )
        assertTrue(ProxyClient.errorMessage(429, "").contains("limit", ignoreCase = true))
        assertTrue(ProxyClient.errorMessage(500, """{"error":"boom"}""").contains("boom"))
        assertTrue(ProxyClient.errorMessage(502, "").contains("502"))
    }
}
