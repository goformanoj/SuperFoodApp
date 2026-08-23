package com.jarvis.os.ui.components

import kotlin.math.abs

/**
 * What the universe is made of, and what colour it is.
 *
 * **The universe owns its own light.** Everything below the orb used to be drawn
 * in the app theme's accent and highlight, which is why *"the dimensions 60-70%
 * look the same"* — two places with identical colours read as one place with the
 * furniture moved, however different their geometry. A forge dimension is not
 * obliged to be orange. Colour is now generated per place, from its own seed, and
 * the theme reaches no further than the orb you pinched to get in.
 *
 * Compose-free, like the orb specs, so `scripts/jvmcheck` runs it in
 * milliseconds. Colour is the single easiest thing to get subtly wrong — a hue
 * that wraps past 1.0, a saturation that collapses to grey, two "different"
 * palettes that land three degrees apart — and all of that is arithmetic.
 */

/**
 * A colour, as plain floats.
 *
 * Deliberately not `androidx.compose.ui.graphics.Color`: one import would put
 * this whole file behind the Compose exclusion and out of reach of the only gate
 * that runs before CI. The renderer converts at the last moment.
 */
data class Ink(val r: Float, val g: Float, val b: Float) {
    companion object {
        val WHITE = Ink(1f, 1f, 1f)

        /**
         * Hue/saturation/lightness to RGB. [h] wraps, so callers may add freely
         * without remembering to take a remainder — a hue of 1.2 is 0.2, and the
         * alternative is a silent black at every wrap.
         */
        fun hsl(h: Float, s: Float, l: Float): Ink {
            val hue = ((h % 1f) + 1f) % 1f
            val sat = s.coerceIn(0f, 1f)
            val lum = l.coerceIn(0f, 1f)
            val c = (1f - abs(2f * lum - 1f)) * sat
            val x = c * (1f - abs((hue * 6f) % 2f - 1f))
            val m = lum - c / 2f
            val (r, g, b) = when ((hue * 6f).toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return Ink(r + m, g + m, b + m)
        }
    }

    fun mix(other: Ink, t: Float): Ink {
        val k = t.coerceIn(0f, 1f)
        return Ink(r + (other.r - r) * k, g + (other.g - g) * k, b + (other.b - b) * k)
    }
}

/**
 * The light of one dimension.
 *
 * Four inks rather than one accent, because a place needs a hot centre, a
 * structure colour, a gas colour and something dark to cut lanes with — and if
 * any two of those are the same hue the whole thing flattens into a wash.
 */
data class DimensionPalette(
    /** The hot centre: near-white, tinted. */
    val core: Ink,
    /** Arms, rings, bodies — the structure you actually read. */
    val arm: Ink,
    /** The cloud it sits in. Deliberately far from [arm] on the wheel. */
    val gas: Ink,
    /** Highlights and young stars. The accent inside the accent. */
    val spark: Ink,
    /** Where on the wheel this place sits, kept so two neighbours can be compared. */
    val hue: Float,
)

/**
 * Each kind of star anchors a FAMILY of colour, and the seed moves within it.
 *
 * Anchoring rather than randomising outright is the point: a blue giant should
 * always be a cold blue-white place and a lava-lit red dwarf should always be
 * warm, or the labels on the chart become lies. The jitter is what stops two blue
 * giants being the same blue.
 */
fun paletteFor(branch: Int, kind: StarKind): DimensionPalette {
    val anchor = when (kind) {
        StarKind.BlueGiant -> 0.55f // cyan into blue
        StarKind.RedDwarf -> 0.02f // red into amber
        StarKind.Binary -> 0.12f // gold
        StarKind.Pulsar -> 0.68f // electric violet-blue
        StarKind.Protostar -> 0.90f // rose into magenta
        StarKind.WhiteDwarf -> 0.48f // pale steel green-cyan
    }
    // Enough jitter that two of a kind differ, not so much that a red dwarf can
    // come out green.
    val hue = anchor + (OrbMath.unitRandom(branch * 7717 + 13) - 0.5f) * 0.13f
    // The gas sits a long way round the wheel from the structure. Adjacent hues
    // are what made every previous dimension read as one colour with shading.
    val gasShift = if (OrbMath.unitRandom(branch * 3301 + 7) > 0.5f) 0.42f else -0.38f
    val sparkShift = if (OrbMath.unitRandom(branch * 4409 + 3) > 0.5f) 0.09f else -0.11f

    return DimensionPalette(
        core = Ink.hsl(hue, 0.35f, 0.93f),
        arm = Ink.hsl(hue, OrbMath.range(branch * 911 + 5, 0.62f, 0.92f), 0.62f),
        gas = Ink.hsl(hue + gasShift, OrbMath.range(branch * 1223 + 9, 0.45f, 0.80f), 0.48f),
        spark = Ink.hsl(hue + sparkShift, 0.85f, 0.72f),
        hue = ((hue % 1f) + 1f) % 1f,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  PLANETS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A world you can actually look at.
 *
 * *"allow the user to explore the different kind of planets"* — so the bodies on
 * an orbit stop being lit dots the moment they are big enough to show anything.
 * Eight kinds, each with a surface that is drawn differently rather than tinted
 * differently: bands, craters, coastlines, cracks, ice, a ring system.
 */
enum class PlanetKind(
    val label: String,
    /** One line, shown when the planet is close enough to read about. */
    val summary: String,
    /** Base hue offset from the dimension's own, so worlds vary within a place. */
    val hueShift: Float,
    /** How large it runs relative to its neighbours. */
    val bulk: Float,
) {
    GasGiant("GAS GIANT", "Banded, enormous, and nothing solid anywhere in it.", 0.06f, 1.9f),
    Rocky("ROCKY", "Cratered and airless. Every impact it ever took is still there.", -0.04f, 0.8f),
    Ocean("OCEAN", "Cloud over deep water, with continents breaking the surface.", 0.30f, 1.05f),
    Lava("LAVA", "Too young to have cooled. The crust is still cracking open.", -0.10f, 0.9f),
    Ice("ICE", "Frozen through, and bright enough to see from the next orbit out.", 0.24f, 0.95f),
    Ringed("RINGED", "A disc of debris, wider than the world holding it.", 0.10f, 1.3f),
    Barren("BARREN", "Dust, and the long shadow of its own sunrise.", -0.02f, 0.75f),
    Shattered("SHATTERED", "It did not survive whatever happened here.", 0.16f, 0.85f),
    ;

    companion object {
        /**
         * Which worlds a dimension grows.
         *
         * Not every kind everywhere: a place where lava worlds and ice worlds are
         * equally likely has no climate, and reads as random rather than as
         * somewhere. Each star kind gets a shortlist that suits it.
         */
        fun formingIn(star: StarKind?): List<PlanetKind> = when (star) {
            null -> entries.toList()
            StarKind.BlueGiant -> listOf(GasGiant, Shattered, Barren, Ringed)
            StarKind.RedDwarf -> listOf(Rocky, Barren, Ice, Ocean)
            StarKind.Binary -> listOf(Shattered, Rocky, GasGiant, Ringed)
            StarKind.Pulsar -> listOf(Shattered, Barren, Rocky)
            StarKind.Protostar -> listOf(Lava, Rocky, GasGiant, Shattered)
            StarKind.WhiteDwarf -> listOf(Ice, Barren, Rocky, Ringed)
        }
    }
}

/** One world, generated rather than stored. */
data class PlanetSpec(
    val kind: PlanetKind,
    val designation: String,
    /** Surface bands, craters or continents — how many, whatever the kind draws. */
    val features: Int,
    /** Axial tilt in radians, so no two are posed alike. */
    val tilt: Float,
    /** Ring system, for the kinds that can have one. */
    val rings: Int,
    val moons: Int,
    /** Hue offset from the dimension's own. */
    val hue: Float,
    /** 0 dark, 1 bright — how much light it returns. */
    val albedo: Float,
)

/** The world at [seed], in a dimension belonging to [star]. */
fun planetFor(seed: Int, star: StarKind?): PlanetSpec {
    val choices = PlanetKind.formingIn(star)
    val kind = choices[(OrbMath.unitRandom(seed * 6151 + 17) * choices.size).toInt()
        .coerceAtMost(choices.size - 1)]
    val canRing = kind == PlanetKind.Ringed || kind == PlanetKind.GasGiant
    return PlanetSpec(
        kind = kind,
        designation = UniverseMath.designationFor(seed * 31 + 5),
        features = when (kind) {
            PlanetKind.GasGiant -> 5 + (OrbMath.unitRandom(seed * 71 + 3) * 5).toInt()
            PlanetKind.Rocky, PlanetKind.Barren -> 6 + (OrbMath.unitRandom(seed * 71 + 3) * 8).toInt()
            PlanetKind.Ocean -> 3 + (OrbMath.unitRandom(seed * 71 + 3) * 4).toInt()
            PlanetKind.Lava -> 5 + (OrbMath.unitRandom(seed * 71 + 3) * 6).toInt()
            PlanetKind.Ice -> 2 + (OrbMath.unitRandom(seed * 71 + 3) * 3).toInt()
            PlanetKind.Ringed -> 3 + (OrbMath.unitRandom(seed * 71 + 3) * 3).toInt()
            PlanetKind.Shattered -> 7 + (OrbMath.unitRandom(seed * 71 + 3) * 9).toInt()
        },
        tilt = OrbMath.range(seed * 97 + 11, -0.7f, 0.7f),
        rings = if (canRing) 2 + (OrbMath.unitRandom(seed * 53 + 7) * 4).toInt() else 0,
        moons = (OrbMath.unitRandom(seed * 43 + 19) * 3.4f).toInt(),
        hue = kind.hueShift + (OrbMath.unitRandom(seed * 29 + 23) - 0.5f) * 0.06f,
        albedo = when (kind) {
            PlanetKind.Ice -> OrbMath.range(seed * 13 + 2, 0.80f, 0.98f)
            PlanetKind.Ocean -> OrbMath.range(seed * 13 + 2, 0.55f, 0.75f)
            PlanetKind.GasGiant -> OrbMath.range(seed * 13 + 2, 0.50f, 0.72f)
            PlanetKind.Lava -> OrbMath.range(seed * 13 + 2, 0.30f, 0.48f)
            else -> OrbMath.range(seed * 13 + 2, 0.22f, 0.45f)
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  GALAXIES
// ─────────────────────────────────────────────────────────────────────────────

/** The shape of a galaxy, which is the first thing you see of one. */
enum class GalaxyKind(val label: String) {
    /** Arms winding out of a bright hub. */
    Spiral("SPIRAL"),

    /** A straight bar across the middle, arms trailing from its ends. */
    Barred("BARRED SPIRAL"),

    /** A smooth featureless swarm — old, and finished forming. */
    Elliptical("ELLIPTICAL"),

    /** No structure survived whatever happened to it. */
    Irregular("IRREGULAR"),

    /** A ring with the middle blown out. */
    Lenticular("RING"),
}

/**
 * One galaxy, riding a ring of the JARVIS orb.
 *
 * *"the orbs moving on the main orbs rings are galaxies… count the number of
 * moving orbs on the Jarvis main orb and build galaxies according to that"* — so
 * the count is not a number I chose. It comes from the orb of whichever theme is
 * on, which means the Arc reactor's five rings really do open onto five galaxies
 * and Nebula's three onto three.
 */
data class GalaxySpec(
    /** Which ring of the orb this one rides. Also its identity. */
    val ring: Int,
    val kind: GalaxyKind,
    val designation: String,
    val arms: Int,
    /** How many stars are in it, and therefore how many dimensions it holds. */
    val stars: Int,
    val twist: Float,
    val tilt: Float,
    val palette: DimensionPalette,
)

/**
 * The galaxies of the current orb — one per ring, in ring order.
 *
 * Seeded on the ring INDEX rather than on the theme, so a galaxy is the same
 * galaxy whichever theme you were looking at when you pinched. *"keep the galaxy
 * constant and it doesn't depend on the theme"*: the theme decides how many
 * there are, never what they are.
 */
fun galaxiesFor(ringCount: Int): List<GalaxySpec> = (0 until ringCount.coerceAtLeast(1)).map { ring ->
    val seed = (ring + 1) * 104_729
    val kind = GalaxyKind.entries[(OrbMath.unitRandom(seed + 11) * GalaxyKind.entries.size)
        .toInt().coerceAtMost(GalaxyKind.entries.size - 1)]
    GalaxySpec(
        ring = ring,
        kind = kind,
        designation = UniverseMath.designationFor(seed),
        arms = 2 + (OrbMath.unitRandom(seed + 23) * 4).toInt(),
        stars = 5 + (OrbMath.unitRandom(seed + 37) * 5).toInt(),
        twist = OrbMath.range(seed + 41, 1.4f, 3.6f) *
            (if (OrbMath.unitRandom(seed + 43) > 0.5f) 1f else -1f),
        tilt = OrbMath.range(seed + 47, 0.18f, 0.92f),
        // A galaxy's own colour comes from its ring, not from any star in it.
        palette = paletteFor(seed, StarKind.entries[ring % StarKind.entries.size]),
    )
}

/**
 * The stars inside one galaxy.
 *
 * Their branch is derived from the galaxy AND the index, so no two galaxies share
 * a dimension — which is the property that makes twenty-odd dimensions worth
 * having rather than six repeated.
 */
fun starsIn(galaxy: GalaxySpec): List<StarSpec> {
    val base = (galaxy.ring + 1) * 104_729
    val raw = (0 until galaxy.stars).map { i ->
        val seed = base + i * 7919
        // Placed along the galaxy's own arms rather than scattered, so the chart
        // reads as *inside* a structure instead of as dots over a picture of one.
        val arm = i % galaxy.arms
        val out = 0.22f + (i.toFloat() / galaxy.stars) * 0.72f
        val angle = arm * (OrbMath.TAU / galaxy.arms) + out * galaxy.twist +
            (OrbMath.unitRandom(seed + 5) - 0.5f) * 0.5f
        StarSpec(
            branch = base + i + 1,
            kind = StarKind.entries[(OrbMath.unitRandom(seed + 13) * StarKind.entries.size)
                .toInt().coerceAtMost(StarKind.entries.size - 1)],
            x = 0.5f + kotlin.math.cos(angle) * out * 0.40f,
            y = 0.5f + kotlin.math.sin(angle) * out * 0.40f * galaxy.tilt,
            size = OrbMath.range(seed + 17, 0.7f, 1.35f),
            phase = OrbMath.unitRandom(seed + 19) * OrbMath.TAU,
            designation = UniverseMath.designationFor(seed + 3),
        )
    }
    return spaceApart(raw)
}

/**
 * Push stars apart until none of them overlap.
 *
 * Placing stars along an arm is what makes a galaxy read as a structure rather
 * than as a scatter — but an arm is a curve, and two stars on neighbouring arms
 * can land on top of each other where the curves cross. A test caught exactly
 * that: two stars 0.126 of the frame apart, which is close enough that
 * nearest-wins hit testing gives one of them to the other and a dimension
 * becomes permanently unreachable.
 *
 * Deterministic, so the same galaxy is laid out the same way every visit. The
 * vertical distance is weighted because positions are FRACTIONS of a frame that
 * is about twice as tall as it is wide — without that, stars are spaced
 * generously left-to-right and left touching vertically.
 */
private fun spaceApart(stars: List<StarSpec>): List<StarSpec> {
    val xs = FloatArray(stars.size) { stars[it].x }
    val ys = FloatArray(stars.size) { stars[it].y }
    repeat(6) {
        for (a in stars.indices) {
            for (b in a + 1 until stars.size) {
                val dx = xs[b] - xs[a]
                val dy = (ys[b] - ys[a]) * ASPECT
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d in 0.0001f..UniverseMath.MIN_STAR_GAP) {
                    val push = (UniverseMath.MIN_STAR_GAP - d) * 0.5f
                    xs[a] -= dx / d * push
                    xs[b] += dx / d * push
                    ys[a] -= dy / d * push / ASPECT
                    ys[b] += dy / d * push / ASPECT
                } else if (d <= 0.0001f) {
                    // Exactly coincident: nothing to push along, so nudge by
                    // index. Rare, but it is the one case the loop above cannot
                    // resolve and it would leave two stars welded together.
                    xs[b] += UniverseMath.MIN_STAR_GAP * 0.5f
                }
            }
        }
    }
    return stars.mapIndexed { i, star ->
        star.copy(
            x = xs[i].coerceIn(0.08f, 0.92f),
            y = ys[i].coerceIn(0.12f, 0.88f),
        )
    }
}

/** A phone is about twice as tall as it is wide; the gap has to know that. */
private const val ASPECT = 2f

// ─────────────────────────────────────────────────────────────────────────────
//  LAYER 3 — A STAR SYSTEM
// ─────────────────────────────────────────────────────────────────────────────

/** One world on its orbit, with everything needed to place it in time. */
data class WorldOrbit(
    val planet: PlanetSpec,
    /** Fraction of the system's radius. */
    val orbit: Float,
    /** Signed: some systems have a world going the wrong way round. */
    val speed: Float,
    val phase: Float,
    /** How far the orbit is tipped out of the plane. */
    val tilt: Float,
)

/**
 * What is actually inside a star.
 *
 * Layer three of four: *"any one star, the actual content, the planets and
 * everything"*. Not the endless self-similar shells that used to be here — those
 * were the same structure at every scale, which is elegant and tells you nothing.
 * A system is a place with a fixed cast: a sun, some worlds, a belt of rubble,
 * and the gaps between them.
 *
 * Orbits are spaced GEOMETRICALLY rather than evenly, the way real systems are —
 * each about 1.4x the one inside it. Evenly spaced orbits read as a target, and
 * the crowding toward the star is most of what makes a system look like one.
 */
data class SystemSpec(
    val designation: String,
    val worlds: List<WorldOrbit>,
    /** Where the asteroid belt sits, or 0 for a system without one. */
    val belt: Float,
    val beltDensity: Int,
)

/** The system inside [star]. */
fun systemFor(star: StarSpec): SystemSpec {
    val seed = star.branch * 7919
    // A blue giant burns its inner system away; a red dwarf holds many close in.
    val count = when (star.kind) {
        StarKind.BlueGiant -> 3
        StarKind.RedDwarf -> 6
        StarKind.Binary -> 4
        StarKind.Pulsar -> 3
        StarKind.Protostar -> 5
        StarKind.WhiteDwarf -> 3
    } + (OrbMath.unitRandom(seed + 3) * 3).toInt()

    var radius = OrbMath.range(seed + 5, 0.20f, 0.30f)
    val worlds = (0 until count).map { i ->
        val w = WorldOrbit(
            planet = planetFor(seed + i * 101, star.kind),
            orbit = radius,
            // Inner worlds run faster, as they do. This is what makes a system
            // read as mechanism rather than as a set of rings.
            speed = (0.55f / (radius + 0.18f)) *
                (if (OrbMath.unitRandom(seed + i * 13) > 0.88f) -1f else 1f),
            phase = OrbMath.unitRandom(seed + i * 17) * OrbMath.TAU,
            tilt = OrbMath.range(seed + i * 23, -0.22f, 0.22f),
        )
        radius *= OrbMath.range(seed + i * 29, 1.30f, 1.52f)
        w
    }
    val hasBelt = OrbMath.unitRandom(seed + 41) > 0.35f
    return SystemSpec(
        designation = star.designation,
        worlds = worlds,
        belt = if (hasBelt && worlds.size > 2) {
            // Between two of the outer worlds, which is where one goes.
            val a = worlds[worlds.size - 2].orbit
            val b = worlds[worlds.size - 1].orbit
            (a + b) / 2f
        } else {
            0f
        },
        beltDensity = if (hasBelt) 90 + (OrbMath.unitRandom(seed + 43) * 140).toInt() else 0,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  LAYER 4 — THINGS ON A WORLD
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Something you find by going close enough.
 *
 * Layer four: *"zooming in on the planets i should find things"*. A planet that
 * is only a shaded sphere is a texture — going closer shows you the same thing
 * larger, which is the definition of an anticlimax. These are the reward for
 * travelling: named, described, and specific to the kind of world they are on, so
 * finding a derelict station above a shattered world tells you what happened
 * there.
 */
enum class LandmarkKind(
    val label: String,
    val note: String,
    /** Drawn as a point of light, a ring on the surface, or a scar across it. */
    val shape: LandmarkShape,
) {
    Station("ORBITAL STATION", "Still under power. Nobody has answered in a long time.", LandmarkShape.Beacon),
    Wreck("WRECK", "Something came apart here, and the pieces stayed in formation.", LandmarkShape.Beacon),
    Monolith("MONOLITH", "Too regular to be geology. No inscription anywhere on it.", LandmarkShape.Beacon),
    Settlement("SETTLEMENT", "Lights in a grid, on the night side. Somebody is home.", LandmarkShape.Cluster),
    Ruin("RUIN FIELD", "Foundations in rows, under a few metres of dust.", LandmarkShape.Cluster),
    StormEye("STORM EYE", "A cyclone older than the survey that named it.", LandmarkShape.Eye),
    Caldera("CALDERA", "A crater rim you could put a small sea inside.", LandmarkShape.Eye),
    Canyon("CANYON", "A single fracture, most of the way round the world.", LandmarkShape.Scar),
    Rift("RIFT", "The crust here is still moving apart.", LandmarkShape.Scar),
    Aurora("AURORA", "The magnetic field, made visible over the pole.", LandmarkShape.Veil),
    ;

    companion object {
        /** What can be found on a world of this kind. */
        fun on(planet: PlanetKind): List<LandmarkKind> = when (planet) {
            PlanetKind.GasGiant -> listOf(StormEye, Aurora, Station, Wreck)
            PlanetKind.Rocky -> listOf(Caldera, Canyon, Ruin, Settlement, Monolith)
            PlanetKind.Ocean -> listOf(StormEye, Settlement, Station, Aurora)
            PlanetKind.Lava -> listOf(Caldera, Rift, Canyon)
            PlanetKind.Ice -> listOf(Rift, Canyon, Station, Aurora)
            PlanetKind.Ringed -> listOf(Station, Wreck, StormEye)
            PlanetKind.Barren -> listOf(Caldera, Ruin, Monolith, Wreck)
            PlanetKind.Shattered -> listOf(Wreck, Rift, Monolith, Ruin)
        }
    }
}

/** How a landmark is drawn on the surface. */
enum class LandmarkShape { Beacon, Cluster, Eye, Scar, Veil }

/** One thing, somewhere on a world. */
data class LandmarkSpec(
    val kind: LandmarkKind,
    val designation: String,
    /** Position on the visible disc, as fractions of its radius from the centre. */
    val u: Float,
    val v: Float,
    val size: Float,
    val angle: Float,
)

/**
 * What is on [planet], and where.
 *
 * Positions are inside the unit disc by construction — a landmark placed by two
 * independent coordinates lands outside the sphere about a fifth of the time, and
 * a "surface feature" floating beside the planet is the sort of thing that is
 * obvious on a screen and invisible in a diff.
 */
fun landmarksOn(planet: PlanetSpec): List<LandmarkSpec> {
    val choices = LandmarkKind.on(planet.kind)
    val seed = planet.designation.hashCode()
    val count = 2 + (OrbMath.unitRandom(seed * 13 + 7) * 4).toInt()
    return (0 until count).map { i ->
        val s = seed * 71 + i * 137
        // Polar placement keeps everything on the disc. sqrt spreads them evenly
        // over the AREA rather than bunching them at the middle.
        val radius = kotlin.math.sqrt(OrbMath.unitRandom(s + 1)) * 0.82f
        val theta = OrbMath.unitRandom(s + 2) * OrbMath.TAU
        LandmarkSpec(
            kind = choices[(OrbMath.unitRandom(s + 3) * choices.size).toInt()
                .coerceAtMost(choices.size - 1)],
            designation = UniverseMath.designationFor(s),
            u = kotlin.math.cos(theta) * radius,
            v = kotlin.math.sin(theta) * radius,
            size = OrbMath.range(s + 5, 0.08f, 0.22f),
            angle = OrbMath.unitRandom(s + 7) * OrbMath.TAU,
        )
    }
}
