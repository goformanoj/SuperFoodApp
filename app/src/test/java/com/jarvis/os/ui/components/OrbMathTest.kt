package com.jarvis.os.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The orb renderers cannot be unit-tested — they draw. The maths behind them
 * can, and it is the part that actually has a correctness requirement: a Canvas
 * redraws every frame, so a "random" particle position must be the SAME random
 * every time or the starfield turns into static.
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
    fun `spokes are evenly spaced and close the circle`() {
        val count = 6
        val first = OrbMath.spokeAngle(0, count)
        val wrapped = OrbMath.spokeAngle(count, count)

        assertEquals(0f, first, 1e-5f)
        assertEquals("the last spoke must land back on the first", OrbMath.TAU, wrapped, 1e-4f)

        val step = OrbMath.spokeAngle(1, count) - first
        for (i in 1 until count) {
            val delta = OrbMath.spokeAngle(i, count) - OrbMath.spokeAngle(i - 1, count)
            assertEquals(step, delta, 1e-5f)
        }
    }

    @Test
    fun `a zero-sided shape does not divide by zero`() {
        assertEquals(0f, OrbMath.spokeAngle(3, 0), 0f)
    }

    @Test
    fun `a point at angle zero sits due right of centre`() {
        assertEquals(10f, OrbMath.xAt(0f, 10f), 1e-4f)
        assertEquals(0f, OrbMath.yAt(0f, 10f), 1e-4f)
    }

    @Test
    fun `a spiral runs from its inner radius to its outer`() {
        val count = 20
        assertEquals(5f, OrbMath.spiralRadius(0, count, 5f, 50f), 1e-4f)
        assertEquals(50f, OrbMath.spiralRadius(count - 1, count, 5f, 50f), 1e-4f)

        // and never doubles back on itself
        var previous = -1f
        for (i in 0 until count) {
            val r = OrbMath.spiralRadius(i, count, 5f, 50f)
            assertTrue("radius went backwards at $i", r > previous)
            previous = r
        }
    }

    @Test
    fun `a single-point spiral does not divide by zero`() {
        assertEquals(5f, OrbMath.spiralRadius(0, 1, 5f, 50f), 0f)
    }

    @Test
    fun `breathe starts and ends closed so the loop does not jump`() {
        assertEquals(0f, OrbMath.breathe(0f), 1e-4f)
        assertEquals(0f, OrbMath.breathe(1f), 1e-4f)
        assertEquals(1f, OrbMath.breathe(0.5f), 1e-4f)
    }

    @Test
    fun `breathe stays inside zero to one`() {
        for (step in 0..100) {
            val v = OrbMath.breathe(step / 100f)
            assertTrue("got $v", v in -0.001f..1.001f)
        }
    }

    @Test
    fun `different seeds give different values`() {
        assertNotEquals(OrbMath.unitRandom(1), OrbMath.unitRandom(2))
    }
}
