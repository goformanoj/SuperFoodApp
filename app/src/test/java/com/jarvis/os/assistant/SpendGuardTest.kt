package com.jarvis.os.assistant

import com.jarvis.os.control.ScreenStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trace this guard exists for: "can you go to blinkit and add some bread"
 * produced a plan ending in Tap(Checkout). It only failed to run because an
 * earlier step broke.
 */
class SpendGuardTest {

    private val addBread = listOf(
        ScreenStep.Open("Blinkit"),
        ScreenStep.Tap("Search"),
        ScreenStep.Type("bread"),
        ScreenStep.Enter,
        ScreenStep.Tap("Add to cart"),
        ScreenStep.Tap("Checkout"),
    )

    @Test
    fun `the exact plan from the device trace stops before checkout`() {
        val safe = SpendGuard.apply("can you go to blinkit and add some bread", addBread)
        assertEquals(addBread.dropLast(1), safe)
        assertEquals("Checkout", SpendGuard.stopsAt("can you go to blinkit and add some bread", addBread))
    }

    @Test
    fun `the work the user did ask for still runs`() {
        // Truncating the whole errand would make JARVIS useless for the part that
        // was requested. Searching, typing and adding to a basket all survive.
        val safe = SpendGuard.apply("add some bread", addBread)
        assertTrue(safe.contains(ScreenStep.Tap("Add to cart")))
        assertTrue(safe.contains(ScreenStep.Type("bread")))
    }

    @Test
    fun `when the user does ask to buy, nothing is dropped`() {
        listOf(
            "checkout my blinkit cart",
            "place the order",
            "buy it",
            "pay now",
            "go ahead and purchase it",
        ).forEach { utterance ->
            assertEquals("'$utterance' asked for it", addBread, SpendGuard.apply(utterance, addBread))
            assertNull(SpendGuard.stopsAt(utterance, addBread))
        }
    }

    @Test
    fun `plans with nothing irreversible are untouched`() {
        val browse = listOf(
            ScreenStep.Open("Blinkit"),
            ScreenStep.Tap("Search"),
            ScreenStep.Type("bread"),
            ScreenStep.Enter,
        )
        assertEquals(browse, SpendGuard.apply("find me bread", browse))
        assertNull(SpendGuard.stopsAt("find me bread", browse))
    }

    @Test
    fun `typing a dangerous word is not spending`() {
        // Only taps commit. Typing "buy one get one" into a search box costs
        // nothing, and dropping the search would break an ordinary errand.
        val typed = listOf(ScreenStep.Tap("Search"), ScreenStep.Type("buy one get one free"))
        assertEquals(typed, SpendGuard.apply("search for buy one get one free", typed))
    }

    @Test
    fun `a label that merely contains an innocent word is not blocked`() {
        val safe = listOf(ScreenStep.Tap("Sender name"), ScreenStep.Tap("Payments help"))
        assertNull(SpendGuard.stopsAt("open payments help", safe))
    }

    @Test
    fun `asksToSpend reads the user's words, not the plan`() {
        assertTrue(SpendGuard.asksToSpend("checkout"))
        assertTrue(SpendGuard.asksToSpend("Place the order please"))
        assertFalse(SpendGuard.asksToSpend("add some bread"))
        assertFalse(SpendGuard.asksToSpend(""))
    }

    @Test
    fun `the stop is explained, never silent`() {
        val message = SpendGuard.explain("Checkout")
        assertTrue(message.contains("Checkout"))
        assertTrue("the user must be able to authorise it", message.contains("Tell me"))
    }
}
