package com.jarvis.os.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInPageTest {

    @Test
    fun theSuccessPageSaysSoAndCarriesTheLogoInline() {
        val html = SignInPage.ok("data:image/png;base64,AAAA")
        assertTrue(html.contains("You're signed in"))
        assertTrue(html.contains("go back to JARVIS"))
        assertTrue("the logo travels inside the page", html.contains("src=\"data:image/png;base64,AAAA\""))
    }

    @Test
    fun noLogoMeansNoBrokenImageAndTheRestStillRenders() {
        val html = SignInPage.ok(null)
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("You're signed in"))
    }

    @Test
    fun thePageNeverReachesOutToTheInternet() {
        // It is served from 127.0.0.1 and must work offline, and must not leak that a sign-in happened.
        for (html in listOf(SignInPage.ok("data:image/png;base64,AAAA"), SignInPage.failed(null, "x"))) {
            assertFalse(html.contains("http://") || html.contains("https://"))
        }
    }

    @Test
    fun aFailureMessageIsEscapedBecauseItComesFromTheRedirect() {
        val html = SignInPage.failed(null, "<script>alert('x')</script>")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("Sign-in didn't finish"))
    }

    @Test
    fun escapeHandlesEveryHtmlSpecialCharacter() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", SignInPage.escape("&<>\"'"))
    }
}
