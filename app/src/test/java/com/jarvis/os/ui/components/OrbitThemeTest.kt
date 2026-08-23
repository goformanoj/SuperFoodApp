package com.jarvis.os.ui.components

import com.jarvis.os.ui.theme.OrbStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The Orbit theme's motion contract.
 *
 * "Everything moves" was the original brief for the themes, and a still orb is a
 * failure this project has already shipped once — the first sprite version was an
 * exact likeness that could not move, and the user rejected it on exactly that
 * ground. These tests exist because motion is the easiest property to destroy in
 * a refactor and the hardest to notice missing in a diff: every one of them still
 * passes if the numbers are merely *present*, so they check the relationships
 * instead.
 */
class OrbitThemeTest {

    private val orbit = specFor(OrbStyle.Orbit)

    @Test
    fun `every ring actually moves`() {
        // A ring with zero precession AND zero spin is a static ellipse. One of
        // those in the set would read as a bug rather than as a design choice.
        orbit.rings.forEachIndexed { i, r ->
            assertTrue("ring $i does not precess", abs(r.precession) > 0f)
            assertTrue("ring $i does not spin", abs(r.spin) > 0f)
        }
    }

    @Test
    fun `inner rings move faster, the way real orbits do`() {
        // This is the choice that makes the rings separate, converge and cross
        // instead of turning as one rigid assembly. Sorting by radius must give
        // strictly decreasing speed.
        val byRadius = orbit.rings.sortedByDescending { it.radius }
        byRadius.zipWithNext().forEachIndexed { i, (outer, inner) ->
            assertTrue(
                "ring $i (r=${outer.radius}) should precess slower than the one inside it " +
                    "(r=${inner.radius}); real orbits speed up as they tighten",
                abs(outer.precession) < abs(inner.precession),
            )
            assertTrue(
                "ring $i should spin slower than the one inside it",
                abs(outer.spin) < abs(inner.spin),
            )
        }
    }

    @Test
    fun `rings turn in opposing directions, so crossings are head-on`() {
        val forward = orbit.rings.count { it.precession > 0f }
        val backward = orbit.rings.count { it.precession < 0f }
        assertTrue("all rings turn the same way — nothing ever crosses", forward > 0 && backward > 0)
    }

    @Test
    fun `no two rings share a speed, so the pattern does not repeat quickly`() {
        val speeds = orbit.rings.map { it.precession }
        assertEquals("two rings precess identically", speeds.size, speeds.toSet().size)
        val spins = orbit.rings.map { it.spin }
        assertEquals("two rings spin identically", spins.size, spins.toSet().size)
    }

    @Test
    fun `each ring leaves room for its travelling highlight to be seen`() {
        // The first draft of this theme set arc to 0.80-0.92, lighting almost the
        // whole ring and leaving the travelling arc nowhere to travel — the least
        // animated theme of the seven, in the one design that is entirely about
        // movement. Anything at or above ~0.75 has effectively no sweep.
        orbit.rings.forEachIndexed { i, r ->
            assertTrue("ring $i is lit end to end (arc=${r.arc}) — no visible sweep", r.arc < 0.75f)
            assertTrue("ring $i is barely lit (arc=${r.arc})", r.arc > 0.2f)
        }
    }

    @Test
    fun `the widest orbits clear the sphere, as they do in the reference`() {
        assertTrue(
            "at least one orbit should pass outside the body",
            orbit.rings.any { it.radius > 1.0f },
        )
        assertTrue("the core should be the largest of any theme", orbit.coreSize >= 0.30f)
    }

    @Test
    fun `Orbit is a scene in space, not a ring assembly`() {
        // This used to assert Orbit carried more dust than any other theme, and
        // that was the wrong property to pin. Mote count is not motion, and it
        // stopped being true the moment Nebula was retuned into a star-forming
        // cloud — which is a correct design decision that a test about ORBIT had
        // no business blocking. What actually distinguishes this theme is the
        // proportions: the biggest body of the four, an orbit that plainly clears
        // it, and a heavy enough dust field to make the space around it occupied.
        val others = OrbStyle.entries.filter { it != OrbStyle.Orbit }.map { specFor(it) }
        assertTrue(
            "Orbit should have the largest body of any theme — it is a world, not a spark",
            others.all { orbit.coreSize > it.coreSize },
        )
        assertTrue(
            "Orbit's widest orbit should be the widest of any theme",
            others.all { orbit.rings.maxOf { r -> r.radius } > it.rings.maxOf { r -> r.radius } },
        )
        assertTrue("Orbit's dust field is too thin to fill the scene", orbit.motes >= 100)
        assertTrue("Orbit's fastest ring should actually be quick", orbit.rings.maxOf { abs(it.spin) } > 2.0f)
    }

    @Test
    fun `every other theme still moves too`() {
        // Cheap insurance: this file exists because motion is easy to lose, and
        // that applies to the six that came before Orbit as well.
        OrbStyle.entries.forEach { style ->
            specFor(style).rings.forEachIndexed { i, r ->
                assertTrue(
                    "$style ring $i is completely static",
                    abs(r.precession) > 0f || abs(r.spin) > 0f,
                )
            }
        }
    }
}
