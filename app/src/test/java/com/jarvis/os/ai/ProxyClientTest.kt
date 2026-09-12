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
    fun `plan and remaining are read from the reply for the usage display`() {
        val body = """{"reply":"ok","plan":"pro","usage":{"input":10,"output":5},"remaining":1999985}"""
        assertEquals("pro", ProxyClient.parsePlan(body))
        assertEquals(1_999_985, ProxyClient.parseRemaining(body))
    }

    @Test
    fun `plan and remaining are null when absent or unparseable`() {
        assertEquals(null, ProxyClient.parsePlan("""{"reply":"ok"}"""))
        assertEquals(null, ProxyClient.parseRemaining("""{"reply":"ok"}"""))
        assertEquals(null, ProxyClient.parsePlan("not json"))
        assertEquals(null, ProxyClient.parseRemaining("not json"))
    }

    @Test
    fun `error messages are speakable and never leak the raw code`() {
        assertTrue(ProxyClient.errorMessage(401, "").contains("verify", ignoreCase = true))
        assertTrue(ProxyClient.errorMessage(403, "").contains("authoris", ignoreCase = true))

        // The over-cap refusal: the Worker ships a ready-to-speak `spoken` line, and
        // that is exactly what JARVIS should say — never the machine `error` token.
        val overCap = ProxyClient.errorMessage(
            429,
            """{"error":"quota_exhausted","spoken":"That's today's AI allowance used up. It resets in about 8 hours.","resetsInSeconds":28800}""",
        )
        assertEquals("That's today's AI allowance used up. It resets in about 8 hours.", overCap)
        assertFalse(overCap.contains("quota_exhausted"))

        // A human `message` is used when there is no `spoken`.
        assertEquals(
            "You've used today's free turns.",
            ProxyClient.errorMessage(429, """{"error":"over_cap","message":"You've used today's free turns."}"""),
        )

        // With no spoken/message, a friendly per-status fallback — and the raw `error`
        // token is NEVER surfaced (a device once showed "quota_exhausted" as the reply).
        val bareCap = ProxyClient.errorMessage(429, "")
        assertTrue(bareCap.contains("allowance", ignoreCase = true) || bareCap.contains("resets", ignoreCase = true))
        val serverErr = ProxyClient.errorMessage(500, """{"error":"boom"}""")
        assertFalse(serverErr.contains("boom"))
        assertTrue(serverErr.contains("server", ignoreCase = true) || serverErr.contains("snag", ignoreCase = true))
        assertFalse(ProxyClient.errorMessage(502, "").contains("502"))
    }
}
