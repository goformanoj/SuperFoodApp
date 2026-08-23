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
    /** Points of light running along the rings — the filigree. */
    val beads: Int = 0,
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
 * | Theme  | Centre  | Rings                          | Extras          |
 * |--------|---------|--------------------------------|-----------------|
 * | Arc    | spark   | five, steep, churning          | —               |
 * | Forge  | spark   | six fine wires, heat-graded    | spokes, beads   |
 * | Nebula | diffuse | three drifting through gas     | gas lobes       |
 * | Orbit  | spark   | one broad disc, two crossing   | dense dust      |
 *
 * Arc and Orbit share a centre KIND and are in no danger of looking alike: one
 * is a 0.17 spark inside five churning rings, the other a 0.44 body under a disc
 * that reaches past the frame. The shape of a theme is carried by proportion at
 * least as much as by which routine draws its middle.
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

    // FORGE — restored. "you ruined forge, take it back to its previous version
    // then make it better", and that is exactly the right reading of what went
    // wrong: the rebuild replaced the design instead of improving it. Six fine
    // fast rings and 24 hairline spokes IS the filigree, and swapping it for
    // three heavy bands and a molten lump threw away the thing being asked to
    // improve.
    //
    // So the six rings and the spokes are back verbatim. What is new is additive:
    //
    //  - BEADS. Points of light running along the wires at their own speeds. A
    //    filigree is fine metal with light caught in it, and the rings had the
    //    metal and none of the light — this is the one element that makes a thin
    //    ring read as jewellery rather than as a drawn circle.
    //  - A HEAT GRADIENT across the set. The rings ran 0.15..0.55 warmth almost
    //    at random; they now climb steadily from near-white at the hub to deep
    //    gold at the rim, so the assembly reads as one object cooling outward
    //    rather than as six unrelated circles.
    //
    // Same radii, same tilts, same speeds, same spoke count, same core.
    OrbStyle.Filigree -> Orb3DSpec(
        rings = listOf(
            Ring3D(0.98f, 0.14f, 0.06f, 0.22f, 0.75f, 0.95f, 1.6f, 0.20f),
            Ring3D(0.86f, -0.18f, 0.10f, -0.34f, -1.05f, 0.80f, 1.5f, 0.22f),
            Ring3D(0.72f, 0.24f, -0.12f, 0.48f, 1.35f, 0.64f, 1.7f, 0.26f),
            Ring3D(0.58f, -0.30f, 0.18f, -0.62f, -1.70f, 0.48f, 1.6f, 0.30f),
            Ring3D(0.44f, 0.36f, -0.22f, 0.80f, 2.10f, 0.30f, 1.5f, 0.36f),
            Ring3D(0.30f, -0.40f, 0.26f, -1.00f, -2.60f, 0.12f, 1.4f, 0.44f),
        ),
        motes = 40, coreSize = 0.19f, core = CoreKind.Spark,
        spokes = 24, beads = 26,
    )

    // NEBULA — "needs a little bit change in its orb, it's just like weird rn",
    // and the fault is that it had no shape at all. Two faint rings, a core made
    // of five drifting veils and seven big lobes is a design with nothing to
    // focus on: every part of it was soft, so the eye had nowhere to land and it
    // read as a smudge rather than as an object.
    //
    // The cloud stays — that is the theme — but it now has something IN it:
    //
    //  - A third ring, and all three brighter and narrower, so there is real
    //    geometry drifting through the gas instead of two hints of it.
    //  - A compact core: three tight veils instead of five broad ones, so the
    //    centre is a bright knot the eye settles on rather than more haze.
    //  - Four lobes instead of seven, each smaller. Seven overlapping billows at
    //    that size merged into one flat wash and cancelled each other out.
    //  - The heaviest mote count of the four, which is what makes it read as a
    //    star-forming cloud rather than as coloured fog.
    OrbStyle.Nebula -> Orb3DSpec(
        rings = listOf(
            Ring3D(1.04f, 0.40f, 0.26f, 0.38f, 0.62f, 0.10f, 2.0f, arc = 0.46f, band = 0.05f),
            Ring3D(0.80f, -0.62f, 0.40f, -0.60f, -1.05f, 0.55f, 1.8f, arc = 0.40f, band = 0.06f),
            Ring3D(0.54f, 0.70f, -0.30f, 0.98f, 1.62f, 0.28f, 1.6f, arc = 0.34f, band = 0.07f),
        ),
        motes = 140, coreSize = 0.24f, core = CoreKind.Diffuse,
        lobes = 4,
    )

    // ORBIT — restored, at the user's word: "get back the previous version of
    // the orbit theme, it was better". These are the exact rings and the exact
    // core it had before, down to the tilts.
    //
    // It is drawn SMALLER instead. A ring at 1.55 orb radii cannot fit a frame
    // 1.0 across at 0.86 fill — 1.55 x 0.86 = 1.33 before perspective touches it
    // — so there is no tilt or precession tweak that saves it, and the earlier
    // attempt to pull the ring in to 1.22 changed the proportions that were the
    // thing worth keeping. [fitFor] takes it to 0.657 of the half-frame instead:
    // the same shape, about three quarters the size, and all of it on screen.
    //
    // MOTION is the point of this one, so the speeds follow real orbital
    // mechanics: inner rings are strictly faster than outer ones, which is what
    // makes them visibly separate, converge and cross instead of turning as one
    // rigid assembly. Directions alternate so crossings are head-on, and no two
    // periods are simple multiples, so the pattern takes a long time to repeat.
    OrbStyle.Orbit -> Orb3DSpec(
        rings = listOf(
            // The disc. Widest, slowest, shallowest tilt, thinnest band — a sheet
            // of dust seen nearly edge-on, not wire.
            Ring3D(1.55f, 0.34f, -0.10f, 0.30f, 0.55f, 0.10f, 2.4f, arc = 0.70f, band = 0.035f),
            // Steeply tilted and counter-turning, so it scissors through the disc.
            Ring3D(1.12f, -0.52f, 0.38f, -0.74f, -1.32f, 0.55f, 1.5f, arc = 0.52f, band = 0.05f),
            // Tight and quick, hugging the world without touching it.
            Ring3D(0.78f, 0.66f, 0.08f, 1.48f, 2.55f, 0.30f, 1.1f, arc = 0.40f, band = 0.04f),
        ),
        // The heaviest dust field of any theme — it is a scene in space, and the
        // motes are what make the volume around the sphere read as occupied. The
        // body is by far the largest of the four: the subject is a sphere with
        // rings around it, not a ring assembly with a spark in the middle.
        motes = 120, coreSize = 0.44f, core = CoreKind.Spark,
    )
}
