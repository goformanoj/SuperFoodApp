package com.jarvis.os.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device failure this exists for: the plan aimed at `Tap("Search")` in
 * Blinkit, whose search control is labelled "Search for atta, dal, coke and more".
 * The plan was not wrong — the information needed to write a better one did not
 * exist until the app was open.
 */
class ControlVocabularyTest {

    @Test
    fun `Blinkit's real search label is offered for a generic Search`() {
        val candidates = ControlVocabulary.candidatesFor("com.grofers.customerapp", "Search")
        assertTrue(
            "the literal from the trace must be offered",
            candidates.contains("Search for atta, dal, coke and more"),
        )
    }

    @Test
    fun `the package is matched by fragment, not exact name`() {
        // Blinkit ships as com.grofers.customerapp — an exact-name key would miss
        // it, and every regional or white-label variant besides.
        assertTrue(ControlVocabulary.candidatesFor("com.grofers.customerapp", "search").isNotEmpty())
        assertTrue(ControlVocabulary.candidatesFor("com.grofers.blinkit.partner", "search").isNotEmpty())
    }

    @Test
    fun `an unknown app offers nothing, so behaviour is unchanged`() {
        assertEquals(emptyList<String>(), ControlVocabulary.candidatesFor("com.some.unknown.app", "Search"))
    }

    @Test
    fun `a specific label is left alone`() {
        // Only generic words are translated. A real on-screen name must never be
        // swapped for something else.
        assertNull(ControlVocabulary.intentOf("Beat It"))
        assertNull(ControlVocabulary.intentOf("Proceed to Checkout"))
        assertEquals(
            emptyList<String>(),
            ControlVocabulary.candidatesFor("com.grofers.customerapp", "Beat It"),
        )
    }

    @Test
    fun `the label already tried is never offered back`() {
        // YouTube seeds both "Search YouTube" and "Search"; asking for "Search"
        // must not propose "Search" again — that is the match that just failed.
        val candidates = ControlVocabulary.candidatesFor("com.google.android.youtube", "Search")
        assertFalse(candidates.any { it.equals("Search", ignoreCase = true) })
        assertTrue(candidates.contains("Search YouTube"))
    }

    @Test
    fun `synonyms for the same intent resolve together`() {
        assertEquals(ControlVocabulary.SEARCH, ControlVocabulary.intentOf("search bar"))
        assertEquals(ControlVocabulary.SEARCH, ControlVocabulary.intentOf("Search Box"))
        assertEquals(ControlVocabulary.CART, ControlVocabulary.intentOf("my cart"))
        assertEquals(ControlVocabulary.ADD, ControlVocabulary.intentOf("Add to cart"))
    }

    @Test
    fun `matching ignores case and punctuation`() {
        assertEquals(ControlVocabulary.SEARCH, ControlVocabulary.intentOf("  SEARCH!  "))
        assertTrue(ControlVocabulary.isGeneric("Search"))
        assertFalse(ControlVocabulary.isGeneric("Search for atta, dal, coke and more"))
    }

    @Test
    fun `a null or blank package is handled rather than crashing`() {
        // describeScreen can be called when nothing readable is in front.
        assertEquals(emptyList<String>(), ControlVocabulary.candidatesFor(null, "Search"))
        assertEquals(emptyList<String>(), ControlVocabulary.candidatesFor("", "Search"))
    }

    @Test
    fun `every seeded literal is more specific than the generic word it replaces`() {
        // A seed that just repeats the generic word adds a wasted scan. "Search"
        // under YouTube is legitimate as a SECOND choice, so the rule is only that
        // an app must not offer the generic word as its ONLY candidate.
        for (app in listOf("com.grofers.customerapp", "com.zeptoconsumerapp", "com.zomato")) {
            val candidates = ControlVocabulary.candidatesFor(app, "Search")
            assertTrue("$app offers no real alternative", candidates.isNotEmpty())
            assertTrue(
                "$app's candidates must not all be the generic word",
                candidates.any { !ControlVocabulary.isGeneric(it) },
            )
        }
    }
}
