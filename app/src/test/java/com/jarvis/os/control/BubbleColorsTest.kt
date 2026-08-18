package com.jarvis.os.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the floating orb's colours. The half that checks these still
 * match the real palette is `JarvisPaletteBubbleTest`, which has to live under
 * `ui/` to be allowed to import Compose.
 */
class BubbleColorsTest {

    @Test
    fun `every id resolves to its own scheme`() {
        val schemes = BubbleColors.all()
        assertEquals(BubbleColors.IDS.size, schemes.size)
        assertEquals("two themes share a scheme", schemes.size, schemes.distinct().size)
    }

    @Test
    fun `an unknown id falls back to the default rather than throwing`() {
        assertEquals(BubbleColors.forTheme("arc"), BubbleColors.forTheme(""))
        assertEquals(BubbleColors.forTheme("arc"), BubbleColors.forTheme("nonsense"))
    }

    @Test
    fun `every colour is fully opaque`() {
        // The bubble applies its own alpha per state. A colour that arrived
        // already transparent would multiply down to invisible — a floating orb
        // you cannot see is indistinguishable from one that failed to appear.
        BubbleColors.all().forEach { s ->
            listOf(s.accent, s.secondary, s.highlight, s.background).forEach { c ->
                assertEquals("alpha must be 0xFF", 0xFF, (c shr 24) and 0xFF)
            }
        }
    }

    @Test
    fun `the accent is brighter than the background in every theme`() {
        // The orb is an accent-coloured dome on a background-coloured rim. If a
        // theme ever inverted that, the bubble would read as a hole.
        BubbleColors.all().forEach { s ->
            assertTrue("accent must out-shine the background", luma(s.accent) > luma(s.background))
        }
    }

    private fun luma(c: Int): Int =
        ((c shr 16) and 0xFF) * 299 / 1000 +
            ((c shr 8) and 0xFF) * 587 / 1000 +
            (c and 0xFF) * 114 / 1000
}
