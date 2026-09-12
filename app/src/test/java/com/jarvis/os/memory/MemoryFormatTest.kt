package com.jarvis.os.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Memory tab's grouping and masking — pure, so it is pinned off-device. */
class MemoryFormatTest {

    @Test
    fun `emails are blurred but still recognisable`() {
        assertEquals("g•••@gmail.com", MemoryFormat.masked("goformanoj@gmail.com"))
        assertEquals(
            "email is m•••@work.co",
            MemoryFormat.masked("email is manoj@work.co"),
        )
    }

    @Test
    fun `long digit runs keep only the last three`() {
        assertEquals("•••••••210", MemoryFormat.masked("9876543210"))
        assertEquals("call •••••••210", MemoryFormat.masked("call 9876543210"))
    }

    @Test
    fun `times and years are left alone`() {
        assertEquals("lunch at 1pm", MemoryFormat.masked("lunch at 1pm"))
        assertEquals("born in 2026", MemoryFormat.masked("born in 2026")) // 4 digits, not masked
        assertFalse(MemoryFormat.isSensitive("prefers short answers"))
    }

    @Test
    fun `sensitive facts sort into contact details`() {
        assertEquals(MemoryFormat.Category.CONTACT, MemoryFormat.categorize("phone is 9876543210"))
        assertEquals(MemoryFormat.Category.CONTACT, MemoryFormat.categorize("reach me at a@b.com"))
    }

    @Test
    fun `people beat identity when both words are present`() {
        // "wife" (people) and "name" (identity) both appear — people wins.
        assertEquals(MemoryFormat.Category.PEOPLE, MemoryFormat.categorize("my wife's name is Priya"))
        assertEquals(MemoryFormat.Category.IDENTITY, MemoryFormat.categorize("call me sir"))
        assertEquals(MemoryFormat.Category.PLACES, MemoryFormat.categorize("lives in Bangalore, uses IST"))
        assertEquals(MemoryFormat.Category.PREFERENCES, MemoryFormat.categorize("prefers short answers"))
        assertEquals(MemoryFormat.Category.OTHER, MemoryFormat.categorize("the sky is blue"))
    }

    @Test
    fun `grouped keeps category order and drops empty buckets`() {
        val groups = MemoryFormat.grouped(
            listOf(
                "call me sir",            // identity
                "prefers short answers",  // preferences
                "my sister is Anu",       // people
                "email a@b.com",          // contact
            ),
        )
        // Contact first, then identity, then people, then preferences — no places bucket.
        assertEquals(
            listOf(
                MemoryFormat.Category.CONTACT,
                MemoryFormat.Category.IDENTITY,
                MemoryFormat.Category.PEOPLE,
                MemoryFormat.Category.PREFERENCES,
            ),
            groups.map { it.category },
        )
        assertTrue(groups.first { it.category == MemoryFormat.Category.IDENTITY }.facts.contains("call me sir"))
    }
}
