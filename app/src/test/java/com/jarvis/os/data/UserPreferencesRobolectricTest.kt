package com.jarvis.os.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The learned-fact store round-trips through real SharedPreferences + org.json,
 * so it needs Robolectric. Covers the dedup / cap / oldest-out behaviour the
 * engine relies on, plus the background-wake default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserPreferencesRobolectricTest {

    private lateinit var prefs: UserPreferences

    @Before
    fun setUp() {
        prefs = UserPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `remember ignores case-insensitive duplicates`() {
        assertTrue(prefs.remember("Call me sir"))
        assertFalse(prefs.remember("call me SIR"))
        assertEquals(1, prefs.learnedFacts().size)
    }

    @Test
    fun `forget removes every fact mentioning the topic`() {
        prefs.remember("the playlist is called peak")
        prefs.remember("wake me at seven")
        assertEquals(1, prefs.forget("peak"))
        assertEquals(listOf("wake me at seven"), prefs.learnedFacts())
    }

    @Test
    fun `over the cap, the oldest fact is dropped, not the newest`() {
        for (i in 1..(UserPreferences.MAX_FACTS + 3)) prefs.remember("fact number $i")
        val facts = prefs.learnedFacts()
        assertEquals(UserPreferences.MAX_FACTS, facts.size)
        assertFalse("oldest evicted", facts.any { it == "fact number 1" })
        assertTrue("newest kept", facts.last().endsWith("${UserPreferences.MAX_FACTS + 3}"))
    }

    @Test
    fun `background wake defaults on and persists a change`() {
        assertTrue(prefs.backgroundWake)
        prefs.backgroundWake = false
        assertFalse(UserPreferences(ApplicationProvider.getApplicationContext()).backgroundWake)
    }

    @Test
    fun `custom instructions persist`() {
        prefs.customInstructions = "keep replies short"
        assertEquals("keep replies short", UserPreferences(ApplicationProvider.getApplicationContext()).customInstructions)
    }
}
