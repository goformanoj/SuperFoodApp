package com.jarvis.os.ui.components

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp

/**
 * How finely an orb is drawn.
 *
 * Separate from [OrbDetail] because it is the CACHE KEY. The theme picker
 * animates a card's orb between 88dp and 104dp when it is selected, so keying
 * the buffers on the raw size would rebuild them on every frame of that
 * animation — replacing the allocation problem this exists to solve. A quality
 * band is stable across the whole animation.
 */
enum class OrbQuality(val segments: Int, val chunks: Int, val moteScale: Float) {
    /** Picker previews. Below ~120dp a ring spans too few pixels for 96 segments and 32 to differ. */
    Low(32, 8, 0.28f),
    Medium(48, 12, 0.5f),
    /** The home screen orb. */
    High(96, 24, 1f),
    ;

    companion object {
        fun forSize(size: Dp): OrbQuality = when {
            size.value >= 200f -> High
            size.value >= 120f -> Medium
            else -> Low
        }
    }
}

/**
 * Scratch space for drawing one orb, held across frames by the composable.
 *
 * The theme picker shows six orbs at once and Settings lagged. Measured, six
 * previews cost about 2,000 draw calls and 14,600 object allocations per frame —
 * 880,000 allocations a second, which is GC thrash, which is what lag feels
 * like. Two separate faults: every ring rebuilt its geometry into fresh Lists
 * each frame, and a 92dp preview was drawn at the same 96 segments and 24 chunks
 * as the 280dp home orb, where the extra detail is invisible at a third the size.
 *
 * Now the geometry is written into these buffers instead, and detail follows
 * size: about 345 draw calls and no allocations.
 */
class OrbDetail(quality: OrbQuality) {

    val segments: Int = quality.segments
    val chunks: Int = quality.chunks
    val moteScale: Float = quality.moteScale

    /** Three rings of `x, y, depth` triples: the band's outer, inner and centre. */
    val outer = FloatArray((segments + 1) * 3)
    val inner = FloatArray((segments + 1) * 3)
    val mid = FloatArray((segments + 1) * 3)

    /**
     * One Path, rewound between uses. A Path per chunk was several hundred more
     * allocations a frame across the picker, and only one is ever in flight —
     * each is filled, drawn, and finished with before the next reset.
     */
    val scratch = Path()

    /**
     * Unit-sphere positions for the mote field, built when the count changes and
     * not once a frame. The rings got this treatment when Settings lagged; the
     * motes were missed, and went on allocating a list and a vector per point per
     * frame for every orb on screen.
     */
    private var moteCount = -1
    private var motePoints = FloatArray(0)

    fun motes(count: Int): FloatArray {
        if (count != moteCount) {
            motePoints = FloatArray(count * 3)
            Orb3D.unitSphere(count, motePoints)
            moteCount = count
        }
        return motePoints
    }
}
