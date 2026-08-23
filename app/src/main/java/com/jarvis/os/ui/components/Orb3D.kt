package com.jarvis.os.ui.components

import kotlin.math.PI
import kotlin.math.abs
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
    /**
     * The same distribution as [spherePoints], written into a flat `x, y, z`
     * array on a UNIT sphere.
     *
     * `spherePoints` allocates a `List` and a `Vec3` per point, and the mote
     * field called it **every frame** — 140 objects, plus two more per point for
     * the rotation and one for the projection, so around 560 short-lived
     * allocations a frame and roughly 34,000 a second. The geometry never
     * changes; only the radius it is scaled to does, and that is a multiply.
     *
     * Kept on the unit sphere for exactly that reason: the orb breathes, so the
     * radius is different every frame and caching scaled points would defeat the
     * purpose. The caller multiplies.
     *
     * The same fix `OrbDetail`'s ring buffers already are — the motes were simply
     * missed when it was done.
     */
    fun unitSphere(count: Int, into: FloatArray) {
        if (count <= 0) return
        val golden = PI.toFloat() * (3f - sqrt(5f))
        for (i in 0 until count) {
            val y = 1f - (i.toFloat() / max(count - 1, 1)) * 2f
            val r = sqrt(max(1f - y * y, 0f))
            val theta = golden * i
            into[i * 3] = cos(theta) * r
            into[i * 3 + 1] = y
            into[i * 3 + 2] = sin(theta) * r
        }
    }

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
     * The furthest a ring of [radius] projects from the centre, over every phase
     * of a precession that swings its tilts by [swingX] and [swingY].
     *
     * Sampled, not solved, and it runs the SAME projection [ringInto] does rather
     * than a tidy approximation of it — the whole value of this number is that it
     * agrees with what is actually drawn. A ring only clips at the worst moment
     * of its precession, which is a moment no static inspection of the spec would
     * ever surface: Orbit's widest ring is nominally 1.55 orb radii, and by the
     * time perspective has magnified its near side it reaches 1.46 half-FRAMES,
     * against a drawing area of 1.0. Both edges were cut off on the device and
     * nothing in the numbers said so.
     *
     * The result is in orb radii, and it is scale-invariant: the camera, the
     * focal length and the ring all scale together, so a caller can multiply by
     * whatever pixel radius it likes.
     */
    fun ringExtent(
        radius: Float,
        tiltX: Float,
        tiltY: Float,
        swingX: Float,
        swingY: Float,
        cameraDistance: Float,
        focal: Float,
        tiltSteps: Int = 13,
        angleSteps: Int = 72,
    ): Float {
        var worst = 0f
        val tSteps = max(tiltSteps, 2)
        val aSteps = max(angleSteps, 8)
        for (i in 0 until tSteps) {
            val tx = tiltX - swingX + 2f * swingX * i / (tSteps - 1)
            val cx = cos(tx)
            val sx = sin(tx)
            for (j in 0 until tSteps) {
                val ty = tiltY - swingY + 2f * swingY * j / (tSteps - 1)
                val cy = cos(ty)
                val sy = sin(ty)
                for (k in 0 until aSteps) {
                    val a = TAU * k / aSteps
                    val px = cos(a) * radius
                    val py0 = sin(a) * radius
                    val py = py0 * cx
                    val pz0 = py0 * sx
                    val rx = px * cy + pz0 * sy
                    val rz = -px * sy + pz0 * cy
                    val scale = focal / max(cameraDistance - rz, 0.05f)
                    val ax = abs(rx * scale)
                    val ay = abs(py * scale)
                    if (ax > worst) worst = ax
                    if (ay > worst) worst = ay
                }
            }
        }
        return worst
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
