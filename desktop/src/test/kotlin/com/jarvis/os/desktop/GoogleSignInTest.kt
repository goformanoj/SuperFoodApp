package com.jarvis.os.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInTest {

    @Test
    fun pkceChallengeMatchesTheRfc7636Vector() {
        // RFC 7636 Appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GoogleSignIn.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifiersAreLongUrlSafeAndFresh() {
        val a = GoogleSignIn.newVerifier()
        val b = GoogleSignIn.newVerifier()
        assertTrue(a.length >= 43)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(a, b)
    }

    @Test
    fun authUrlCarriesPkceStateAndLoopbackRedirect() {
        val url = GoogleSignIn.authUrl("cid.apps.googleusercontent.com", "http://127.0.0.1:5123", "CHAL", "st8")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("client_id=cid.apps.googleusercontent.com"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5123"))
        assertTrue(url.contains("scope=openid%20email%20profile"))
        assertTrue(url.contains("code_challenge=CHAL&code_challenge_method=S256"))
        assertTrue(url.contains("state=st8"))
        assertTrue(url.contains("response_type=code"))
    }

    @Test
    fun callbackWithCode() {
        val cb = GoogleSignIn.parseCallback("state=st8&code=4%2F0Abc-xyz&scope=email")
        assertEquals("4/0Abc-xyz", cb.code)
        assertEquals("st8", cb.state)
        assertNull(cb.error)
    }

    @Test
    fun callbackWithError() {
        val cb = GoogleSignIn.parseCallback("error=access_denied&state=st8")
        assertEquals("access_denied", cb.error)
        assertNull(cb.code)
    }

    @Test
    fun faviconAndEmptyRequestsCarryNothing() {
        val cb = GoogleSignIn.parseCallback("")
        assertNull(cb.code); assertNull(cb.error); assertNull(cb.state)
    }

    @Test
    fun idTokenIsReadFromTheTokenResponse() {
        assertEquals("eyJ.x.y", GoogleSignIn.idTokenFrom("""{"access_token":"a","id_token":"eyJ.x.y","expires_in":3599}"""))
        assertNull(GoogleSignIn.idTokenFrom("""{"access_token":"a"}"""))
        assertNull(GoogleSignIn.idTokenFrom("not json"))
    }
}
