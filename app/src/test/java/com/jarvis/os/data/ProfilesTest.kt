package com.jarvis.os.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Per-account data partitioning — pure, so the "guest sees only guest data" rule is pinned. */
class ProfilesTest {

    @Test
    fun `signed out is always guest`() {
        assertEquals("guest", Profiles.idFor(null, signedIn = false))
        assertEquals("guest", Profiles.idFor("a@b.com", signedIn = false))
        assertEquals("guest", Profiles.idFor(null, signedIn = true))
        assertEquals("guest", Profiles.idFor("", signedIn = true))
    }

    @Test
    fun `a signed-in email maps to a stable filename-safe id`() {
        assertEquals("u_goforpranjalgmailcom", Profiles.idFor("goforpranjal@gmail.com", signedIn = true))
        // Case-insensitive and punctuation-stripped, so the same address is one partition.
        assertEquals(
            Profiles.idFor("Go.For.Pranjal@Gmail.com", signedIn = true),
            Profiles.idFor("goforpranjal@gmail.com", signedIn = true),
        )
    }

    @Test
    fun `different accounts get different partitions`() {
        val a = Profiles.idFor("a@x.com", true)
        val b = Profiles.idFor("b@x.com", true)
        assertEquals(false, a == b)
        assertEquals(false, a == Profiles.GUEST)
    }

    @Test
    fun `scoped appends the id to the base name`() {
        assertEquals("jarvis_chat__guest", Profiles.scoped("jarvis_chat", "guest"))
        assertEquals("artifacts__u_a", Profiles.scoped("artifacts", "u_a"))
    }
}
