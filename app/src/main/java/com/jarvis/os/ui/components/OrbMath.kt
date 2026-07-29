package com.jarvis.os.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure geometry and pseudo-randomness for the orb renderers.
 *
 * Kept out of the Composables on purpose. A Canvas redraws on every animation
 * frame, so anything that decides WHERE a particle sits must return the same
 * answer every time it is asked — `Math.random()` would scatter the starfield
 * anew sixty times a second and look like static. Everything here is a pure
 * function of its seed, which also makes it the only part of the theme work that
 * can be unit-tested.
 */
object OrbMath {

    /** Deterministic value in `0f..1f` for [seed]. Same seed, same answer, always. */
    fun unitRandom(seed: Int): Float {
        var x = seed * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
    }

    /** Deterministic value in [min]..[max] for [seed]. */
    fun range(seed: Int, min: Float, max: Float): Float = min + unitRandom(seed) * (max - min)

    /** The angle, in radians, of item [index] of [count] evenly spaced around a circle. */
    fun spokeAngle(index: Int, count: Int, offsetDegrees: Float = 0f): Float {
        if (count <= 0) return 0f
        return (index.toFloat() / count) * TAU + offsetDegrees * DEG_TO_RAD
    }

    /** x offset of a point at [angle] radians and [radius] from a centre. */
    fun xAt(angle: Float, radius: Float): Float = cos(angle) * radius

    /** y offset of a point at [angle] radians and [radius] from a centre. */
    fun yAt(angle: Float, radius: Float): Float = sin(angle) * radius

    /**
     * Radius of a point [index] along a spiral of [count] points, running from
     * [inner] to [outer]. Used for the nebula arms.
     */
    fun spiralRadius(index: Int, count: Int, inner: Float, outer: Float): Float {
        if (count <= 1) return inner
        val t = index.toFloat() / (count - 1)
        return inner + (outer - inner) * t
    }

    /**
     * A value that eases in and out of `0f..1f` as [phase] runs 0..1, so a pulse
     * has no visible jump when the animation loops.
     */
    fun breathe(phase: Float): Float = (1f - cos(phase * TAU)) / 2f

    const val TAU: Float = (2.0 * PI).toFloat()
    const val DEG_TO_RAD: Float = (PI / 180.0).toFloat()
}
