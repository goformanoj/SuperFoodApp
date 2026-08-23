package com.jarvis.os.ui.components

import kotlin.math.PI

/**
 * Deterministic pseudo-randomness for the backdrop star field.
 *
 * Kept out of the Composables on purpose. A Canvas redraws on every animation
 * frame, so anything that decides WHERE a star sits must return the same answer
 * every time it is asked — `Math.random()` would scatter the field anew sixty
 * times a second and look like static.
 *
 * The geometry helpers that used to live here went with the hand-drawn orbs they
 * served; the artwork carries that shape now.
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

    const val TAU: Float = (2.0 * PI).toFloat()

    /** Half a turn. Kotlin's own `PI` is a Double, and mixing the two here is noise. */
    const val PI_F: Float = PI.toFloat()
}
