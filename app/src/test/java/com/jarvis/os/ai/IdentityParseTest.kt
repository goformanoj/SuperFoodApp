package com.jarvis.os.ai

import org.junit.Assert.assertEquals
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
}
