package com.jarvis.os.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two Firebase auth endpoints answer in different casing — identitytoolkit
 * (sign-up) is camelCase, securetoken (refresh) is snake_case — and `expiresIn` is a
 * STRING of seconds in both. These pin that both are parsed correctly, since a
 * mismatch would silently break token refresh on device. org.json → Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentityParseTest {

    @Test
    fun `parseSignUp reads the camelCase anonymous sign-up response`() {
        val body = """
            {"kind":"identitytoolkit#SignupNewUserResponse","idToken":"ID123",
             "refreshToken":"REF123","expiresIn":"3600","localId":"uid-abc"}
        """.trimIndent()
        val t = Identity.parseSignUp(body)
        assertEquals("ID123", t.idToken)
        assertEquals("REF123", t.refreshToken)
        assertEquals(3600L, t.expiresInSec)
    }

    @Test
    fun `parseRefresh reads the snake_case token-refresh response`() {
        val body = """
            {"access_token":"ID999","expires_in":"3600","token_type":"Bearer",
             "refresh_token":"REF999","id_token":"ID999","user_id":"uid-abc","project_id":"1"}
        """.trimIndent()
        val t = Identity.parseRefresh(body)
        assertEquals("ID999", t.idToken)
        assertEquals("REF999", t.refreshToken)
        assertEquals(3600L, t.expiresInSec)
    }

    @Test
    fun `a non-numeric or missing expiry falls back to an hour`() {
        val t = Identity.parseSignUp("""{"idToken":"a","refreshToken":"b"}""")
        assertEquals(3600L, t.expiresInSec)
    }

    // ---- Google sign-in (Part E) ----------------------------------------------

    @Test
    fun `the link payload carries the google token, provider, and the anonymous token`() {
        val body = Identity.buildIdpPayload("GTOK", anonIdToken = "ANON")
        val o = JSONObject(body)
        assertEquals("id_token=GTOK&providerId=google.com", o.getString("postBody"))
        assertTrue(o.getBoolean("returnSecureToken"))
        assertEquals("ANON", o.getString("idToken")) // present ⇒ Firebase links to the anon uid
    }

    @Test
    fun `without an anonymous token the payload omits idToken, so it is a plain sign-in`() {
        val o = JSONObject(Identity.buildIdpPayload("GTOK", anonIdToken = null))
        assertFalse(o.has("idToken"))
    }

    @Test
    fun `parseIdp reads the federated sign-in response`() {
        val body = """
            {"federatedId":"https://accounts.google.com/1","providerId":"google.com",
             "localId":"uid-google-1","idToken":"IDG","refreshToken":"REFG","expiresIn":"3600",
             "email":"user@example.com","isNewUser":false}
        """.trimIndent()
        val r = Identity.parseIdp(body)
        assertEquals("IDG", r.idToken)
        assertEquals("REFG", r.refreshToken)
        assertEquals("uid-google-1", r.localId)
        assertEquals("user@example.com", r.email)
        assertEquals(3600L, r.expiresInSec)
        assertFalse(r.isNewUser)
    }

    @Test
    fun `parseIdp treats a missing email as none rather than empty`() {
        val r = Identity.parseIdp("""{"idToken":"a","refreshToken":"b","localId":"u"}""")
        assertNull(r.email)
    }

    @Test
    fun `an already-linked google account is recognised as a conflict, so we sign in instead`() {
        val err = """{"error":{"code":400,"message":"FEDERATED_USER_ID_ALREADY_LINKED"}}"""
        assertTrue(Identity.isLinkConflict(400, err))
        assertTrue(Identity.isLinkConflict(400, """{"error":{"message":"EMAIL_EXISTS"}}"""))
    }

    @Test
    fun `a genuine error is not mistaken for a link conflict`() {
        assertFalse(Identity.isLinkConflict(400, """{"error":{"message":"INVALID_IDP_RESPONSE"}}"""))
        assertFalse(Identity.isLinkConflict(200, """{"idToken":"a"}""")) // success is never a conflict
    }
}
