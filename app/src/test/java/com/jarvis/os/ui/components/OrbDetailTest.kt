package com.jarvis.os.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings lagged because six live orbs were each drawn at full detail and each
 * rebuilt its geometry into fresh Lists every frame. These pin the two things
 * that fix has to get right.
 */
class OrbDetailTest {

    @Test
    fun `the picker's animating card never changes quality band`() {
        // The whole point of keying the buffers on a BAND rather than the size: a
        // selected card animates its orb between 88dp and 104dp, and a key that
        // moved with the size would reallocate on every frame of that animation —
        // reintroducing exactly the problem being fixed.
        var size = 88f
        while (size <= 104f) {
            assertSame(
                "quality must not change across the selection animation",
                OrbQuality.Low,
                OrbQuality.forSize(size.dp),
            )
            size += 0.5f
        }
    }

    @Test
    fun `the home orb gets full detail and previews do not`() {
        assertEquals(OrbQuality.High, OrbQuality.forSize(280.dp))
        assertEquals(OrbQuality.Low, OrbQuality.forSize(92.dp))
        assertEquals(OrbQuality.Low, OrbQuality.forSize(104.dp))
    }

    @Test
    fun `detail rises with size and never falls`() {
        val bands = listOf(60.dp, 92.dp, 119.dp, 120.dp, 199.dp, 200.dp, 400.dp)
            .map { OrbQuality.forSize(it) }
        bands.zipWithNext { a, b ->
            assertTrue("$a then $b goes backwards", b.segments >= a.segments)
            assertTrue("$a then $b goes backwards", b.chunks >= a.chunks)
            assertTrue("$a then $b goes backwards", b.moteScale >= a.moteScale)
        }
    }

    @Test
    fun `chunks divide the segments exactly`() {
        // drawRing steps the ring in `segments / chunks` strides. A remainder
        // would leave a wedge of every ring undrawn.
        OrbQuality.entries.forEach {
            assertEquals("${it.name}: ${it.segments} / ${it.chunks}", 0, it.segments % it.chunks)
        }
    }

    @Test
    fun `buffers are big enough for the geometry written into them`() {
        // Orb3D.ringInto requires (segments + 1) * 3 floats and throws otherwise,
        // so an undersized buffer is a crash on the first frame.
        OrbQuality.entries.forEach { quality ->
            val detail = OrbDetail(quality)
            val needed = (quality.segments + 1) * 3
            listOf(detail.outer, detail.inner, detail.mid).forEach {
                assertTrue("${quality.name}: ${it.size} < $needed", it.size >= needed)
            }
            assertEquals(quality.segments, detail.segments)
        }
    }

    @Test
    fun `every quality band is reachable from some size`() {
        val reached = listOf(50.dp, 92.dp, 150.dp, 300.dp).map { OrbQuality.forSize(it) }.toSet()
        assertEquals("an unreachable band is dead configuration", OrbQuality.entries.toSet(), reached)
    }
}
