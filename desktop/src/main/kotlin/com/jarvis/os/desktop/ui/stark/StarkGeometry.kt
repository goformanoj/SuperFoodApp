package com.jarvis.os.desktop.ui.stark

import kotlin.math.abs
import kotlin.math.sin

/**
 * The numbers behind the Stark world, kept out of the drawing so they can be tested: where the
 * floor's cross lines sit, how far a hex cell is from the corner it fades out of, how tall a
 * data column is at a given moment.
 */
internal object StarkGeometry {

    /**
     * Fractions down the floor (0 = the horizon, 1 = the bottom edge) for its cross lines.
     * They bunch up at the horizon — that squaring is what makes a flat grid read as a floor
     * receding into the distance — and drift toward the viewer as [phase] goes 0 → 1, then
     * wrap seamlessly, because row 0 at phase 1 is exactly row 1 at phase 0.
     */
    fun floorRows(phase: Float, count: Int = 14): List<Float> {
        val p = phase - kotlin.math.floor(phase)
        return List(count) { i ->
            val d = (i + p) / count
            d * d
        }
    }

    /** 1 at the corner, 0 at [reach] and beyond, easing out (squared) so cells vanish softly. */
    fun fade(distance: Float, reach: Float): Float {
        if (reach <= 0f) return 0f
        val f = (1f - distance / reach).coerceIn(0f, 1f)
        return f * f
    }

    /** Height of data column [index] at time [t], as a fraction of its full height (never fully empty). */
    fun barHeight(index: Int, t: Float): Float =
        0.18f + 0.82f * abs(sin(t * (0.5f + 0.09f * index) + index * 1.3f))

    /** A cheap deterministic hash in [0, 1) — flicker without a random generator that differs per run. */
    fun hash01(a: Int, b: Int, c: Int): Float {
        var h = a * 374761393 + b * 668265263 + c * 2147483647
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF) / 2147483648f
    }
}
