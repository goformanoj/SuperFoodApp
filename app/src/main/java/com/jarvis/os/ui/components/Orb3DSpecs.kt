package com.jarvis.os.ui.components

import com.jarvis.os.ui.theme.OrbStyle

/**
 * What each theme's orb is *made of*.
 *
 * **Compose-free on purpose.** These are the numbers that decide whether an orb
 * fits its frame, whether it moves, and whether two themes look like the same
 * object recoloured — and they are pure arithmetic, so `scripts/jvmcheck` can
 * compile and test them here instead of waiting twenty minutes for CI to draw
 * them. That mattered immediately: the Orbit orb shipped clipped at both edges,
 * and the arithmetic that proves it ([extentFor]) now runs before the push.
 *
 * The renderer that turns these into light lives in `Orb3DRenderer.kt`.
 */

/** One ring of the orb, in three dimensions. */
data class Ring3D(
    /** Fraction of the orb radius. */
    val radius: Float,
    /** Resting tilt about X and Y, radians. */
    val tiltX: Float,
    val tiltY: Float,
    /** How fast this ring precesses and spins, as multiples of the master clock. */
    val precession: Float,
    val spin: Float,
    /** 0 = accent, 1 = highlight; blended, so a ring can be any mix. */
    val warmth: Float,
    val width: Float,
    /** Fraction of the ring lit brightly — the travelling arc. 1f lights it all. */
    val arc: Float = 0.34f,
    /** Half-thickness of the luminous band, as a fraction of the ring's radius. */
    val band: Float = 0.10f,
)

/**
 * How a theme's centre is drawn.
 *
 * Added because "make the orb designs different across all themes, not only the
 * background" was a fair complaint about four sets of tilted rings with different
 * counts. Ring geometry alone cannot carry a theme's identity at thumbnail size —
 * the centre is the biggest, brightest thing on screen, and four different
 * *kinds* of centre separate the themes at a glance in a way five rings versus
 * three never did.
 */
enum class CoreKind {
    /** A hard white spark in a tight iris. The reactor. */
    Spark,

    /** A turbulent molten mass with a crust that cracks and glows. The forge. */
    Molten,

    /** No surface at all — layered gas falling off to nothing. The nebula. */
    Diffuse,

    /** A lit sphere with a terminator and a limb. The world under the rings. */
    World,
}

/** Everything a theme needs to build its orb. */
data class Orb3DSpec(
    val rings: List<Ring3D>,
    val motes: Int,
    val coreSize: Float,
    val core: CoreKind,
    /** Radial struts across the core, for the mechanical designs. */
    val spokes: Int = 0,
    /** Billowing gas lobes around the centre — the cloud designs. */
    val lobes: Int = 0,
    /** Sparks drifting up and out off the core — the forge. */
    val embers: Int = 0,
)

/**
 * How far the tilts wander either side of their resting value.
 *
 * The renderer precesses each ring by adding `sin(...) * PRECESS_SWING_X` and
 * `cos(...) * PRECESS_SWING_Y` to its tilts, which is what makes a ring read as a
 * gyroscope rather than a fixed ellipse. Declared here rather than buried in the
 * draw loop because [extentFor] has to sweep exactly the same range — a ring only
 * clips at the worst moment of its precession, and any drift between these two
 * numbers would let it clip at a phase the fit check never looked at.
 */
const val PRECESS_SWING_X = 0.45f
const val PRECESS_SWING_Y = 0.55f

/** The breathing swell, at its fullest with the microphone loud. */
const val MAX_BREATHE = 1.07f

/**
 * The furthest any part of [style]'s orb reaches from the centre, as a multiple
 * of the orb radius, over every phase of its precession.
 *
 * This is the answer to "the orbit theme's orb is getting cut". The orb radius
 * was a fixed 0.86 of the half-frame for every theme, which is fine for a design
 * whose widest ring sits at 0.95 — and hopeless for one whose widest ring sits at
 * 1.55 and is then magnified further by perspective as its near side swings
 * toward the camera. Orbit reached 1.46 half-frames and lost both edges.
 *
 * Sampled rather than solved. A closed form exists, but it has to account for two
 * tilts, a perspective divide and the band's half-width, and the version of it I
 * would trust is the one that agrees with the renderer's own projection — so this
 * runs that projection over the whole range instead. It is called once per style
 * and cached, so the cost is irrelevant; being *right* is not.
 */
fun extentFor(style: OrbStyle): Float = EXTENTS.getOrPut(style) {
    val spec = specFor(style)
    if (spec.rings.isEmpty()) {
        spec.coreSize * MAX_BREATHE
    } else {
        spec.rings.maxOf { ring ->
            Orb3D.ringExtent(
                radius = ring.radius * (1f + ring.band),
                tiltX = ring.tiltX,
                tiltY = ring.tiltY,
                swingX = PRECESS_SWING_X,
                swingY = PRECESS_SWING_Y,
                cameraDistance = CAMERA_DISTANCE,
                focal = FOCAL,
            )
        } * MAX_BREATHE
    }
}

/**
 * The fraction of the half-frame an orb of [style] may occupy without any part of
 * it leaving the frame.
 *
 * Capped at [PREFERRED_FILL] so a design that already fits is not shrunk to make
 * room it does not need. Only a theme that would genuinely clip gets smaller, and
 * with the four specs as they stand none of them does — this is the guard rail
 * that keeps the next retune from shipping cut off again, not a correction being
 * applied today.
 */
fun fitFor(style: OrbStyle): Float =
    minOf(PREFERRED_FILL, SAFE_EDGE / extentFor(style))

/** How much of the half-frame an orb fills when nothing forces it smaller. */
const val PREFERRED_FILL = 0.86f

/**
 * Slightly inside the frame, not exactly on it. Flares and the mote field add a
 * few pixels beyond the ring geometry this measures, and a design that ends
 * exactly on the boundary reads as clipped even when it is not.
 */
const val SAFE_EDGE = 0.96f

/** The renderer's camera, as multiples of the orb radius. */
const val CAMERA_DISTANCE = 3.4f
const val FOCAL = 2.55f

private val EXTENTS = HashMap<OrbStyle, Float>()

/**
 * The four orbs.
 *
 * They are four different OBJECTS, not one object with four ring counts. That
 * was the note — *"make the orb designs different across all themes, not only the
 * background"* — and it was accurate about what had happened: every theme was a
 * stack of tilted rings around a glow, so the accent colour was doing nearly all
 * the work of telling them apart, exactly as the backdrops had been before they
 * were split.
 *
 * | Theme  | Centre  | Rings                         | Extras          |
 * |--------|---------|-------------------------------|-----------------|
 * | Arc    | spark   | five, steep, churning         | —               |
 * | Forge  | molten  | three heavy bands, near-flat  | struts, embers  |
 * | Nebula | diffuse | two soft dust lanes           | gas lobes       |
 * | Orbit  | world   | one broad disc, two crossing  | dense dust      |
 */
internal fun specFor(style: OrbStyle): Orb3DSpec = when (style) {

    // ARC REACTOR — untouched, and deliberately so: "loved the arc reactor
    // theme". Five rings at steep opposing tilts churning around a hard white
    // spark. It is the busiest and the most mechanical of the four, and it is the
    // one that was already right.
    OrbStyle.Reactor -> Orb3DSpec(
        rings = listOf(
            Ring3D(0.95f, 0.30f, 0.10f, 0.60f, 1.00f, 0.0f, 2.4f, 0.30f),
            Ring3D(0.80f, -0.55f, 0.40f, -0.85f, -1.35f, 0.9f, 2.8f, 0.26f),
            Ring3D(0.64f, 0.75f, -0.30f, 1.10f, 1.70f, 0.1f, 2.6f, 0.34f),
            Ring3D(0.48f, -0.25f, 0.80f, -1.30f, -2.10f, 0.8f, 2.2f, 0.40f),
            Ring3D(0.33f, 0.50f, 0.20f, 1.60f, 2.60f, 0.2f, 1.8f, 0.50f),
        ),
        motes = 60, coreSize = 0.17f, core = CoreKind.Spark,
    )

    // FORGE — rebuilt. It was six thin fast rings and 24 hairline spokes, which
    // is a *filigree*: delicate, cold, and near enough to Arc Reactor that only
    // the colour separated them. A forge is the opposite of delicate.
    //
    // So: a big molten mass at the centre, and only three rings — heavy, slow,
    // and tilted barely at all, so they read as tempered steel bands lying almost
    // flat around it rather than as gyroscopes. Wide bands (0.14-0.20 against
    // Arc's 0.26-0.50 arcs) make them solid metal, not wire. Twelve thick struts
    // instead of 24 thin ones, and embers lifting off the crust — the one moving
    // element no other theme has, and the thing that says *hot* rather than
    // *lit*.
    OrbStyle.Filigree -> Orb3DSpec(
        rings = listOf(
            Ring3D(1.02f, 0.16f, 0.05f, 0.20f, 0.42f, 0.20f, 3.4f, arc = 0.62f, band = 0.14f),
            Ring3D(0.80f, -0.22f, 0.12f, -0.31f, -0.66f, 0.45f, 3.8f, arc = 0.55f, band = 0.16f),
            Ring3D(0.56f, 0.28f, -0.10f, 0.52f, 1.05f, 0.70f, 3.2f, arc = 0.48f, band = 0.20f),
        ),
        motes = 24, coreSize = 0.34f, core = CoreKind.Molten,
        spokes = 12, embers = 64,
    )

    // NEBULA — rebuilt. It was three ordinary rings around a small glow, i.e. Arc
    // Reactor with two fewer rings, which is why it read as a recolour.
    //
    // A nebula has no hard geometry in it at all. The centre is DIFFUSE — layered
    // gas with no surface and no edge — and there are only two rings, both very
    // wide, very thin and mostly lit, so they read as dust lanes drifting across
    // the cloud rather than as objects. The structure is carried by seven gas
    // lobes instead, which no other theme has. Fewest rings, no hard core, no
    // metal: the exact inverse of Forge, which is the point.
    OrbStyle.Nebula -> Orb3DSpec(
        rings = listOf(
            Ring3D(1.10f, 0.40f, 0.26f, 0.34f, 0.52f, 0.10f, 1.3f, arc = 0.72f, band = 0.06f),
            Ring3D(0.84f, -0.66f, 0.40f, -0.55f, -0.88f, 0.55f, 1.1f, arc = 0.66f, band = 0.08f),
        ),
        motes = 110, coreSize = 0.30f, core = CoreKind.Diffuse,
        lobes = 7,
    )

    // ORBIT — a ringed WORLD: one broad disc well clear of the body, two tighter
    // rings crossing it, and by far the largest sphere of the four, because the
    // subject here is the planet and not the rings.
    //
    // THE WIDEST RING IS 1.22, DOWN FROM 1.55. That is the fix for "the orbit
    // theme's orb is getting cut". A ring at 1.55 with steep tilts reaches 1.46
    // half-frames once perspective magnifies its near side, against a drawing
    // area of 1.0 — so it lost both edges on a real phone. Pulling it in to 1.22
    // and easing the tilts brings the worst case to 1.09, which fits inside
    // [SAFE_EDGE] at full size, so the orb did not have to be shrunk to fix it.
    // [fitFor] now guards this mechanically for every theme.
    //
    // MOTION is the point of this one, so the speeds follow real orbital
    // mechanics: inner rings are strictly faster than outer ones, which is what
    // makes them visibly separate, converge and cross instead of turning as one
    // rigid assembly. Directions alternate so crossings are head-on, and no two
    // periods are simple multiples, so the pattern takes a long time to repeat.
    // `OrbitThemeTest` pins all of that.
    OrbStyle.Orbit -> Orb3DSpec(
        rings = listOf(
            // The disc: widest, slowest, shallowest, thinnest band — a sheet of
            // dust seen nearly edge-on, not wire.
            Ring3D(1.22f, 0.30f, -0.08f, 0.30f, 0.55f, 0.10f, 2.4f, arc = 0.70f, band = 0.035f),
            // Steeply tilted and counter-turning, so it scissors through the disc.
            Ring3D(0.94f, -0.46f, 0.30f, -0.74f, -1.32f, 0.55f, 1.5f, arc = 0.52f, band = 0.05f),
            // Tight and quick, hugging the world without touching it.
            Ring3D(0.70f, 0.60f, 0.08f, 1.48f, 2.55f, 0.30f, 1.1f, arc = 0.40f, band = 0.04f),
        ),
        // The heaviest dust field of any theme — it is a scene in space, and the
        // motes are what make the volume around the sphere read as occupied.
        motes = 130, coreSize = 0.44f, core = CoreKind.World,
    )
}
