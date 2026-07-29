package com.jarvis.os.ui.components

import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.OrbStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ramps are measured from the reference renders, so the risk is not that a
 * colour is ugly — it is that a ramp is short, shared, or indexed out of range,
 * any of which shows up as a theme quietly wearing another theme's light.
 */
class ThemeArtTest {

    @Test
    fun `every orb style has its own ramp`() {
        val ramps = OrbStyle.entries.map { ThemeArt.ramp(it) }
        ramps.forEach { assertEquals("ramps are ten stops, centre outward", 10, it.size) }
        assertEquals("two styles share a ramp", ramps.size, ramps.toSet().size)
    }

    @Test
    fun `every theme's style resolves to a ramp`() {
        JarvisPalette.entries.forEach {
            assertEquals(10, ThemeArt.ramp(it.orbStyle).size)
        }
    }

    @Test
    fun `sampling the ends returns the end stops`() {
        OrbStyle.entries.forEach { style ->
            val ramp = ThemeArt.ramp(style)
            assertEquals(ramp.first(), ThemeArt.at(style, 0f))
            assertEquals(ramp.last(), ThemeArt.at(style, 1f))
        }
    }

    @Test
    fun `sampling outside the range is clamped, not crashed`() {
        OrbStyle.entries.forEach { style ->
            val ramp = ThemeArt.ramp(style)
            assertEquals(ramp.first(), ThemeArt.at(style, -3f))
            assertEquals(ramp.last(), ThemeArt.at(style, 9f))
        }
    }

    @Test
    fun `sampling between stops interpolates`() {
        // Rings sit at arbitrary radii, so the ramp has to give a colour at any
        // fraction rather than only at its ten measured points.
        val style = OrbStyle.Reactor
        val ramp = ThemeArt.ramp(style)
        val mid = ThemeArt.at(style, 0.5f / 9f)
        assertNotEquals(ramp[0], mid)
        assertTrue(mid.red in minOf(ramp[0].red, ramp[1].red)..maxOf(ramp[0].red, ramp[1].red))
    }

    @Test
    fun `ramps are bright enough to glow when blended additively`() {
        OrbStyle.entries.forEach { style ->
            ThemeArt.ramp(style).forEachIndexed { i, c ->
                val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
                assertTrue("$style stop $i is nearly black ($luminance)", luminance > 0.10f)
            }
        }
    }

    @Test
    fun `every style has a backdrop colour and it is dark`() {
        OrbStyle.entries.forEach { style ->
            val c = ThemeArt.backdrop(style)
            val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
            assertTrue("$style backdrop is too light ($luminance)", luminance < 0.35f)
        }
        val all = OrbStyle.entries.map { ThemeArt.backdrop(it) }
        assertEquals("two styles share a backdrop", all.size, all.toSet().size)
    }
}
