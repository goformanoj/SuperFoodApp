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
    fun `there are four themes, each with a world of its own`() {
        // Down from seven on 2026-08-19. Lattice, Prism and Core were cut because
        // each shared its backdrop geometry with a neighbour — Lattice drew Arc's
        // wireframe globe, Prism and Core drew the same geodesic shell — so they
        // were recolours rather than designs. The four that remain each own a
        // distinct centrepiece AND a distinct world.
        //
        // The count is asserted so a theme cannot be dropped by accident: the id
        // is persisted, so losing one silently downgrades whoever had it selected.
        assertEquals(4, JarvisPalette.entries.size)
    }

    @Test
    fun `Orbit pairs cyan with violet rather than the usual cyan and blue`() {
        // The whole point of the theme: the reference's sphere runs cyan at the
        // rim and violet on the far side, and that pair is what the greeting
        // gradient is cut from. If it drifts back to blue it stops being this
        // design and becomes a recolour of Arc.
        val orbit = JarvisPalette.fromId("orbit")
        assertEquals(OrbStyle.Orbit, orbit.orbStyle)
        assertTrue("accent should read cyan", orbit.accent.blue > 0.8f && orbit.accent.green > 0.6f)
        assertTrue("secondary should read violet", orbit.secondary.blue > 0.8f && orbit.secondary.red > 0.35f)
    }

    @Test
    fun `Orbit is the darkest ground, since its design is mostly empty space`() {
        val orbit = JarvisPalette.fromId("orbit")
        fun lum(c: androidx.compose.ui.graphics.Color) = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        JarvisPalette.entries.filter { it != orbit }.forEach {
            assertTrue(
                "orbit must stay the darkest ground; ${it.id} is darker",
                lum(orbit.background) <= lum(it.background),
            )
        }
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
    fun `the wordmark is bright enough to read over the artwork`() {
        // It is drawn over the middle of the orb, which is the busiest and often
        // brightest part of every design.
        JarvisPalette.entries.forEach {
            val c = it.wordmark
            val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
            assertTrue("${it.id} wordmark is too dark ($luminance)", luminance > 0.70f)
        }
    }

    @Test
    fun `the default is a real entry`() {
        assertTrue(JarvisPalette.Default in JarvisPalette.entries)
    }

    @Test
    fun `a theme that was removed falls back instead of crashing`() {
        // Anyone who had Lattice, Prism or Core selected still has that id in
        // their preferences. `fromId` must land them somewhere real — a persisted
        // string outliving the enum it named is the ordinary case after a cut,
        // not an edge case.
        listOf("lattice", "prism", "core").forEach {
            assertEquals("removed id \"$it\" should fall back", JarvisPalette.Default, JarvisPalette.fromId(it))
        }
    }
}
