package com.jarvis.os.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The orb is drawn, so it cannot be tested — but the geometry behind it can, and
 * that is where a mistake hides. A sign error in a rotation or a projection does
 * not crash; it produces something subtly wrong that only shows on a device,
 * which is the slowest possible feedback loop in this project.
 */
class Orb3DTest {

    private fun length(v: Vec3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    @Test
    fun `rotation preserves length`() {
        val v = Vec3(3f, -4f, 12f)
        val before = length(v)
        assertEquals(before, length(Orb3D.rotateX(v, 0.7f)), 1e-3f)
        assertEquals(before, length(Orb3D.rotateY(v, -1.9f)), 1e-3f)
        assertEquals(before, length(Orb3D.rotateZ(v, 2.4f)), 1e-3f)
    }

    @Test
    fun `rotating by zero changes nothing`() {
        val v = Vec3(1f, 2f, 3f)
        listOf(Orb3D.rotateX(v, 0f), Orb3D.rotateY(v, 0f), Orb3D.rotateZ(v, 0f)).forEach {
            assertEquals(v.x, it.x, 1e-5f)
            assertEquals(v.y, it.y, 1e-5f)
            assertEquals(v.z, it.z, 1e-5f)
        }
    }

    @Test
    fun `a full turn returns to the start`() {
        val v = Vec3(5f, -2f, 1f)
        val round = Orb3D.rotateY(v, Orb3D.TAU)
        assertEquals(v.x, round.x, 1e-2f)
        assertEquals(v.y, round.y, 1e-2f)
        assertEquals(v.z, round.z, 1e-2f)
    }

    @Test
    fun `rotating about an axis leaves that axis alone`() {
        assertEquals(7f, Orb3D.rotateX(Vec3(7f, 1f, 1f), 1.1f).x, 1e-4f)
        assertEquals(7f, Orb3D.rotateY(Vec3(1f, 7f, 1f), 1.1f).y, 1e-4f)
        assertEquals(7f, Orb3D.rotateZ(Vec3(1f, 1f, 7f), 1.1f).z, 1e-4f)
    }

    @Test
    fun `a quarter turn about X sends Y to Z`() {
        val p = Orb3D.rotateX(Vec3(0f, 1f, 0f), Orb3D.TAU / 4f)
        assertEquals(0f, p.y, 1e-4f)
        assertEquals(1f, p.z, 1e-4f)
    }

    @Test
    fun `nearer points project larger`() {
        val near = Orb3D.project(Vec3(1f, 0f, 5f), cameraDistance = 20f, focal = 10f)
        val far = Orb3D.project(Vec3(1f, 0f, -5f), cameraDistance = 20f, focal = 10f)

        assertTrue("perspective is what stops a tilted ring looking flat", near.x > far.x)
        assertTrue(near.scale > far.scale)
    }

    @Test
    fun `a point behind the camera cannot explode`() {
        // Clamped rather than allowed to divide by zero or flip sign: a ring
        // precessing through the camera plane would otherwise fling geometry
        // across the screen for a frame.
        val p = Orb3D.project(Vec3(1f, 1f, 100f), cameraDistance = 20f, focal = 10f)
        assertTrue("got ${p.x}", p.x.isFinite() && abs(p.x) < 1e5f)
        assertTrue(p.scale > 0f)
    }

    @Test
    fun `projection keeps the centre at the centre`() {
        val p = Orb3D.project(Vec3(0f, 0f, 0f), cameraDistance = 20f, focal = 10f)
        assertEquals(0f, p.x, 1e-5f)
        assertEquals(0f, p.y, 1e-5f)
    }

    @Test
    fun `an untilted ring is a circle of the right radius`() {
        val pts = Orb3D.ring(radius = 10f, segments = 32, tiltX = 0f, tiltY = 0f, spin = 0f)
        pts.forEach {
            assertEquals("every point sits on the circle", 10f, hypot(it.x, it.y), 1e-3f)
            assertEquals("an untilted ring is flat", 0f, it.z, 1e-4f)
        }
    }

    @Test
    fun `a ring closes on itself`() {
        val pts = Orb3D.ring(10f, 24, 0.4f, 0.2f, 0.9f)
        assertEquals("caller strokes it as a closed path", 25, pts.size)
        val first = pts.first()
        val last = pts.last()
        assertEquals(first.x, last.x, 1e-3f)
        assertEquals(first.y, last.y, 1e-3f)
        assertEquals(first.z, last.z, 1e-3f)
    }

    @Test
    fun `a tilted ring has real depth`() {
        val pts = Orb3D.ring(10f, 32, tiltX = 0.9f, tiltY = 0f, spin = 0f)
        val spread = pts.maxOf { it.z } - pts.minOf { it.z }
        assertTrue("a tilted ring must have a near and a far side, got $spread", spread > 5f)
    }

    @Test
    fun `a ring cannot be degenerate however few segments are asked for`() {
        assertTrue(Orb3D.ring(5f, 0, 0f, 0f, 0f).size >= 4)
        assertTrue(Orb3D.ring(5f, -3, 0f, 0f, 0f).size >= 4)
    }

    @Test
    fun `sphere points sit on the sphere and spread over it`() {
        val pts = Orb3D.spherePoints(200, 10f)
        assertEquals(200, pts.size)
        pts.forEach { assertEquals(10f, length(it), 0.4f) }

        // Evenly spread, not clumped at the poles the way naive lat/long is.
        val upper = pts.count { it.y > 3.3f }
        val middle = pts.count { it.y in -3.3f..3.3f }
        val lower = pts.count { it.y < -3.3f }
        listOf(upper, middle, lower).forEach {
            assertTrue("uneven spread: $upper/$middle/$lower", it > 40)
        }
    }

    @Test
    fun `asking for no sphere points is not an error`() {
        assertTrue(Orb3D.spherePoints(0, 10f).isEmpty())
        assertTrue(Orb3D.spherePoints(-5, 10f).isEmpty())
        assertEquals(1, Orb3D.spherePoints(1, 10f).size)
    }

    @Test
    fun `wrap01 handles the backward-spinning rings`() {
        // Kotlin's % truncates toward zero and returns a negative result for a
        // negative input. Four rings spin backwards; using % directly made their
        // phase negative, which drove the travelling arc's brightness above 1 and
        // lit the whole ring permanently.
        assertEquals(0.25f, Orb3D.wrap01(0.25f), 1e-5f)
        assertEquals(0.75f, Orb3D.wrap01(-0.25f), 1e-5f)
        assertEquals(0.5f, Orb3D.wrap01(-3.5f), 1e-5f)
        assertEquals(0.5f, Orb3D.wrap01(7.5f), 1e-5f)
        assertEquals(0f, Orb3D.wrap01(0f), 1e-5f)
        assertEquals(0f, Orb3D.wrap01(-2f), 1e-5f)
    }

    @Test
    fun `wrap01 never leaves the unit range`() {
        var v = -12.3f
        while (v < 12.3f) {
            val w = Orb3D.wrap01(v)
            assertTrue("wrap01($v) = $w", w >= 0f && w < 1f)
            v += 0.137f
        }
    }

    @Test
    fun `depth runs from nought at the back to one at the front`() {
        assertEquals(0f, Orb3D.depthFactor(-10f, 10f), 1e-4f)
        assertEquals(0.5f, Orb3D.depthFactor(0f, 10f), 1e-4f)
        assertEquals(1f, Orb3D.depthFactor(10f, 10f), 1e-4f)
    }

    @Test
    fun `depth stays inside its range and survives a zero radius`() {
        assertEquals(1f, Orb3D.depthFactor(500f, 10f), 1e-4f)
        assertEquals(0f, Orb3D.depthFactor(-500f, 10f), 1e-4f)
        assertEquals(0.5f, Orb3D.depthFactor(3f, 0f), 1e-4f)
    }
}
