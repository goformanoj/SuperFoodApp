package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The avatar monogram and display label — pure logic on [Identity.Account], so the
 * "the letter should reflect the person" behaviour is pinned off-device.
 */
class AccountInitialTest {

    @Test
    fun `initial prefers the name over the email`() {
        val a = Identity.Account(email = "goforpranjal@gmail.com", isSignedIn = true, name = "Pranjal Sharma")
        assertEquals('P', a.initial)
    }

    @Test
    fun `initial falls back to the email when there is no name`() {
        val a = Identity.Account(email = "goforpranjal@gmail.com", isSignedIn = true, name = null)
        assertEquals('G', a.initial)
    }

    @Test
    fun `initial skips leading non-letters`() {
        val a = Identity.Account(email = "  42cats@x.com", isSignedIn = true, name = "  99 Luftballons")
        assertEquals('L', a.initial)
    }

    @Test
    fun `guest with no account shows a neutral glyph`() {
        assertEquals('G', Identity.Account(email = null, isSignedIn = false).initial)
    }

    @Test
    fun `display label prefers name, then email, then a default`() {
        assertEquals("Pranjal", Identity.Account("p@x.com", true, name = "Pranjal").displayLabel())
        assertEquals("p@x.com", Identity.Account("p@x.com", true, name = null).displayLabel())
        assertEquals("Guest", Identity.Account(null, false).displayLabel())
    }
}
