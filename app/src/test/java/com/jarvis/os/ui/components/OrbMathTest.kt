package com.jarvis.os.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The orbs cannot be unit-tested — they are artwork. This can, and it is the
 * part with a real correctness requirement: a Canvas redraws every frame, so a
 * "random" star position must be the SAME random every time, or the backdrop
 * turns into static.
 */
class OrbMathTest {

    @Test
    fun `the same seed always gives the same value`() {
        for (seed in 0 until 200) {
            assertEquals(OrbMath.unitRandom(seed), OrbMath.unitRandom(seed), 0f)
        }
    }

    @Test
    fun `values stay inside zero to one`() {
        for (seed in -500..500) {
            val v = OrbMath.unitRandom(seed)
            assertTrue("seed $seed gave $v", v in 0f..1f)
        }
    }

    @Test
    fun `neighbouring seeds do not give neighbouring values`() {
        // Particles are seeded by index. If consecutive seeds returned similar
        // values they would all land in a line instead of scattering.
        var closePairs = 0
        for (seed in 0 until 200) {
            if (abs(OrbMath.unitRandom(seed) - OrbMath.unitRandom(seed + 1)) < 0.01f) closePairs++
        }
        assertTrue("$closePairs of 200 consecutive pairs were nearly identical", closePairs < 20)
    }

    @Test
    fun `the distribution covers the whole range`() {
        val buckets = IntArray(10)
        for (seed in 0 until 2000) {
            buckets[(OrbMath.unitRandom(seed) * 10).toInt().coerceIn(0, 9)]++
        }
        buckets.forEachIndexed { i, count ->
            assertTrue("bucket $i had only $count of 2000", count > 100)
        }
    }

    @Test
    fun `range maps into the requested band`() {
        for (seed in 0 until 300) {
            val v = OrbMath.range(seed, 2f, 5f)
            assertTrue("got $v", v in 2f..5f)
        }
    }

    @Test
    fun `different seeds give different values`() {
        assertNotEquals(OrbMath.unitRandom(1), OrbMath.unitRandom(2))
    }
}
