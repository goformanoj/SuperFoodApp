package com.jarvis.os.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryActionsTest {

    @Test
    fun `parses the example that prompted the feature`() {
        val (clean, actions) = MemoryActions.parse(
            "Got it, I'll remember that. <<REMEMBER|When the user says Amazon Music they mean chow>>",
        )

        assertEquals("Got it, I'll remember that.", clean)
        assertEquals(
            MemoryAction.Remember("When the user says Amazon Music they mean chow"),
            actions.single(),
        )
    }

    @Test
    fun `parses a forget`() {
        val (_, actions) = MemoryActions.parse("<<FORGET|chow>>")

        assertEquals(MemoryAction.Forget("chow"), actions.single())
    }

    @Test
    fun `several facts in one reply are all kept`() {
        val (_, actions) = MemoryActions.parse(
            "<<REMEMBER|Call the user Manoj>> <<REMEMBER|Prefers YouTube for music>>",
        )

        assertEquals(2, actions.size)
    }

    @Test
    fun `a single closing bracket still works`() {
        val (clean, actions) = MemoryActions.parse("Noted. <<REMEMBER|Call the user sir>")

        assertEquals("Noted.", clean)
        assertEquals(1, actions.size)
    }

    @Test
    fun `an empty fact is ignored`() {
        assertTrue(MemoryActions.parse("<<REMEMBER|>>").second.isEmpty())
        assertTrue(MemoryActions.parse("<<FORGET|  >>").second.isEmpty())
    }

    @Test
    fun `a rambling fact is truncated rather than stored whole`() {
        val long = "x".repeat(400)
        val fact = (MemoryActions.parse("<<REMEMBER|$long>>").second.single() as MemoryAction.Remember).fact

        assertEquals(MemoryActions.MAX_FACT, fact.length)
    }

    @Test
    fun `markers never reach the spoken reply`() {
        val (clean, _) = MemoryActions.parse("Sure. <<REMEMBER|Call the user sir>>")

        assertFalse(clean.contains("<<"))
    }

    @Test
    fun `plain conversation is left untouched`() {
        val chat = "I'll remember that you prefer tea."
        val (clean, actions) = MemoryActions.parse(chat)

        assertTrue(actions.isEmpty())
        assertEquals(chat, clean)
    }

    @Test
    fun `markers are case-insensitive`() {
        assertEquals(1, MemoryActions.parse("<<remember|call me sir>>").second.size)
        assertEquals(1, MemoryActions.parse("<<Forget|sir>>").second.size)
    }
}
