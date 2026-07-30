package com.jarvis.os.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in orb space. The orb sits at the origin; the camera looks down -Z. */
data class Vec3(val x: Float, val y: Float, val z: Float)

/**
 * A [Vec3] flattened to the screen.
 *
 * [depth] is the original Z, kept because it is what shading needs: the far side
 * of a ring must be dimmer and thinner than the near side, and that difference
 * is most of what makes a flat stroke read as a ring in space rather than as an
 * ellipse.
 */
data class Projected(val x: Float, val y: Float, val depth: Float, val scale: Float)

/**
 * The 3D maths behind the orb.
 *
 * The orb is not artwork and not a flat drawing: rings are real circles in three
 * dimensions, tilted, precessing, and projected through a perspective camera.
 * Two earlier approaches failed for want of exactly this — vector shapes drawn
 * flat never looked like the references, and slicing a photograph into bands
 * sheared it into wedges, because a flat image has no depth to rotate through.
 *
 * Pure functions, deliberately: this is the only part of the orb that can be
 * unit-tested, and it is the part where an error is invisible by inspection but
 * obvious on a device.
 */
object Orb3D {

    const val TAU: Float = (2.0 * PI).toFloat()

    fun rotateX(p: Vec3, angle: Float): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        return Vec3(p.x, p.y * c - p.z * s, p.y * s + p.z * c)
    }

    fun rotateY(p: Vec3, angle: Float): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        return Vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c)
    }

    fun rotateZ(p: Vec3, angle: Float): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        return Vec3(p.x * c - p.y * s, p.x * s + p.y * c, p.z)
    }

    /**
     * Perspective projection. [cameraDistance] is how far the eye sits from the
     * origin and [focal] scales the result to pixels.
     *
     * Perspective rather than orthographic on purpose: it is what makes the near
     * side of a tilted ring visibly larger than the far side, and without that a
     * rotating ring looks like a wobbling ellipse.
     */
    fun project(p: Vec3, cameraDistance: Float, focal: Float): Projected {
        // Clamped so a point drifting behind the camera cannot divide by zero or
        // flip sign and fling geometry across the screen.
        val d = max(cameraDistance - p.z, 0.05f)
        val s = focal / d
        return Projected(p.x * s, p.y * s, p.z, s)
    }

    /**
     * Points evenly spaced round a circle of [radius], tilted by [tiltX] then
     * [tiltY], with the sampling offset by [spin].
     *
     * Returns [segments] + 1 points, the last equal to the first, so a caller can
     * stroke it as a closed path without special-casing the join.
     */
    fun ring(
        radius: Float,
        segments: Int,
        tiltX: Float,
        tiltY: Float,
        spin: Float,
    ): List<Vec3> {
        val n = max(segments, 3)
        return (0..n).map { i ->
            val a = (i.toFloat() / n) * TAU + spin
            rotateY(rotateX(Vec3(cos(a) * radius, sin(a) * radius, 0f), tiltX), tiltY)
        }
    }

    /**
     * [count] points spread evenly over a sphere of [radius] by the Fibonacci
     * spiral, which avoids the clumping at the poles that comes from sampling
     * latitude and longitude independently.
     */
    fun spherePoints(count: Int, radius: Float): List<Vec3> {
        if (count <= 0) return emptyList()
        val golden = PI.toFloat() * (3f - sqrt(5f))
        return (0 until count).map { i ->
            val y = 1f - (i.toFloat() / max(count - 1, 1)) * 2f
            val r = sqrt(max(1f - y * y, 0f))
            val theta = golden * i
            Vec3(cos(theta) * r * radius, y * radius, sin(theta) * r * radius)
        }
    }

    /**
     * Projects a whole ring into [out] as `x, y, depth` triples, allocating
     * nothing.
     *
     * [ring] and a `.map { project(it) }` behind it allocate two Lists and two
     * objects per point, every ring, every frame. On the theme picker — six orbs
     * of several rings each — that measured about 14,600 objects per frame, or
     * 880,000 a second, which is what made Settings lag. The maths is identical;
     * only the plumbing changed.
     *
     * [out] must hold at least `(segments + 1) * 3` floats.
     */
    fun ringInto(
        out: FloatArray,
        radius: Float,
        segments: Int,
        tiltX: Float,
        tiltY: Float,
        spin: Float,
        cameraDistance: Float,
        focal: Float,
    ) {
        val n = max(segments, 3)
        require(out.size >= (n + 1) * 3) { "buffer holds ${out.size}, need ${(n + 1) * 3}" }
        val cx = cos(tiltX)
        val sx = sin(tiltX)
        val cy = cos(tiltY)
        val sy = sin(tiltY)
        for (i in 0..n) {
            val a = (i.toFloat() / n) * TAU + spin
            val px = cos(a) * radius
            val py0 = sin(a) * radius
            // rotateX then rotateY, inlined so no Vec3 is created.
            val py = py0 * cx
            val pz0 = py0 * sx
            val rx = px * cy + pz0 * sy
            val rz = -px * sy + pz0 * cy
            val d = max(cameraDistance - rz, 0.05f)
            val scale = focal / d
            val o = i * 3
            out[o] = rx * scale
            out[o + 1] = py * scale
            out[o + 2] = rz
        }
    }

    /**
     * Wraps [v] into `0f..1f`.
     *
     * Kotlin's `%` truncates toward zero, so it returns a NEGATIVE result for a
     * negative left operand — unlike the modulo of most maths write-ups. Four of
     * the rings spin backwards, and using `%` directly made their phase negative,
     * which drove the travelling-arc brightness above 1 and lit the whole ring
     * permanently instead of a moving arc.
     */
    fun wrap01(v: Float): Float {
        val m = v % 1f
        return if (m < 0f) m + 1f else m
    }

    /**
     * Maps a point's [depth] to 0..1, where 1 is nearest the camera. Renderers
     * multiply brightness and stroke width by this, which is what gives a ring
     * its front and back.
     */
    fun depthFactor(depth: Float, radius: Float): Float {
        if (radius <= 0f) return 0.5f
        return ((depth / radius) * 0.5f + 0.5f).coerceIn(0f, 1f)
    }
}
