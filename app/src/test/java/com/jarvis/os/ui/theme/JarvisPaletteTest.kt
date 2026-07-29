package com.jarvis.os.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme is persisted by its [JarvisPalette.id] string, so an id that changed
 * or collided would silently reset a user's choice on the next launch. These
 * tests pin the contract that makes persistence safe.
 */
class JarvisPaletteTest {

    @Test
    fun `there are six themes, one per supplied design`() {
        assertEquals(6, JarvisPalette.entries.size)
    }

    @Test
    fun `ids are unique, since the id is what gets saved`() {
        val ids = JarvisPalette.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every theme round-trips through its id`() {
        JarvisPalette.entries.forEach {
            assertEquals(it, JarvisPalette.fromId(it.id))
        }
    }

    @Test
    fun `an unknown or empty id falls back rather than crashing`() {
        assertEquals(JarvisPalette.Default, JarvisPalette.fromId(""))
        assertEquals(JarvisPalette.Default, JarvisPalette.fromId("no-such-theme"))
        // A theme removed in a future version must not break an existing install.
        assertEquals(JarvisPalette.Default, JarvisPalette.fromId("violet"))
    }

    @Test
    fun `every orb style is used by at least one theme`() {
        val used = JarvisPalette.entries.map { it.orbStyle }.toSet()
        assertEquals(
            "an unused style is dead drawing code",
            OrbStyle.entries.toSet(),
            used,
        )
    }

    @Test
    fun `each theme names a distinct look`() {
        val names = JarvisPalette.entries.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertTrue("a theme needs a name", it.isNotBlank()) }
        JarvisPalette.entries.forEach { assertTrue("${it.id} needs a blurb", it.blurb.isNotBlank()) }
    }

    @Test
    fun `no theme is drawn in a single colour`() {
        // Accent, highlight and background all appear in every renderer; if two
        // of them matched, that theme's geometry would vanish into its backdrop.
        JarvisPalette.entries.forEach {
            assertNotEquals("${it.id}: accent equals background", it.accent, it.background)
            assertNotEquals("${it.id}: highlight equals background", it.highlight, it.background)
            assertNotEquals("${it.id}: accent equals highlight", it.accent, it.highlight)
            assertNotEquals("${it.id}: surface equals background", it.surface, it.background)
        }
    }

    @Test
    fun `backgrounds are dark enough for a light-on-dark UI`() {
        // Every screen draws TextPrimary over these. A pale background would
        // make the whole app unreadable, and only a person would notice.
        JarvisPalette.entries.forEach {
            val c = it.background
            val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
            assertTrue("${it.id} background is too light ($luminance)", luminance < 0.15f)
        }
    }

    @Test
    fun `accents are bright enough to read against their own background`() {
        JarvisPalette.entries.forEach {
            val c = it.accent
            val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
            assertTrue("${it.id} accent is too dark ($luminance)", luminance > 0.35f)
        }
    }

    @Test
    fun `every theme ships its own artwork`() {
        // The art IS the orb, so a missing or shared drawable means a theme
        // silently wearing another theme's face.
        val ids = JarvisPalette.entries.map { it.art }
        ids.forEach { assertTrue("a theme has no artwork", it != 0) }
        assertEquals("two themes share one drawable", ids.size, ids.toSet().size)
    }

    @Test
    fun `the default is a real entry`() {
        assertTrue(JarvisPalette.Default in JarvisPalette.entries)
    }
}
