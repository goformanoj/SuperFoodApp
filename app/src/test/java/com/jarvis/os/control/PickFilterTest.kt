package com.jarvis.os.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device trace this guards: "tell me the best reel here" → <<PICK|the top Reel>>
 * chose the bottom-nav "Reels" tab (a bare string that matches "the top Reel") and
 * opened it instead of playing a reel. The chooser only sees strings, so the nav
 * chrome is dropped before it ever gets there.
 */
class PickFilterTest {

    @Test
    fun `the Reels tab is dropped when real reels are present`() {
        val onScreen = listOf(
            "Reels", // the bottom-nav tab — the trap
            "Comedy sketch · 1.2M views",
            "Cooking in 60s · 890K views",
            "Travel vlog · 2.1M views",
            "Home",
            "Search",
        )
        val choices = PickFilter.preferContent(onScreen)
        assertFalse("the Reels nav tab must be gone", choices.contains("Reels"))
        assertFalse(choices.contains("Home"))
        assertFalse(choices.contains("Search"))
        assertTrue("real content stays", choices.contains("Comedy sketch · 1.2M views"))
        assertEquals(3, choices.size)
    }

    @Test
    fun `a screen that is nothing but navigation is left intact`() {
        // Never hand the chooser an empty list — if all it can see is chrome, let it
        // pick from the chrome rather than fail with nothing to choose.
        val allNav = listOf("Home", "Search", "Reels", "Profile", "Notifications")
        assertEquals(allNav, PickFilter.preferContent(allNav))
    }

    @Test
    fun `matching is case- and space-insensitive`() {
        val choices = PickFilter.preferContent(listOf("  REELS ", "A real reel", "home"))
        assertEquals(listOf("A real reel"), choices)
    }

    @Test
    fun `content whose name merely contains a nav word survives`() {
        // Only a whole-label nav match is dropped; a video titled "My search for peace"
        // is content, not the Search tab.
        val choices = PickFilter.preferContent(listOf("Search", "My search for peace"))
        assertEquals(listOf("My search for peace"), choices)
    }
}
