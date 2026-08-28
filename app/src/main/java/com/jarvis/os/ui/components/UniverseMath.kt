package com.jarvis.os.ui.components

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * The arithmetic of an endless zoom.
 *
 * The brief was "I can keep going deeper and deeper", and the honest way to read
 * that is *without a bottom* — not eight hand-authored levels that run out. A
 * finite ladder is also the version that gets found out immediately: the first
 * thing anyone does with a zoom is keep pinching.
 *
 * So there are no levels stored anywhere. There is one continuous [zoom] number,
 * and at any moment four **shells** are on screen at once: the one you came from
 * (swollen, fading out), the one you are in, and two nested inside it. Zooming
 * in by exactly 1.0 grows every shell by [SCALE], which puts the child precisely
 * where its parent was — so the next frame is the same picture one level down,
 * and the seam is invisible because there is nothing to seam. `depth` is just
 * `floor(zoom)`, and each shell's content is generated from `depth + j`, so the
 * shell you fly into is the *same* shell after the counter ticks over.
 *
 * That identity is the one property worth defending, and it is what
 * `UniverseMathTest` pins: the set of (seed, scale, alpha) drawn just before a
 * whole-number crossing must equal the set drawn just after it. Get that wrong
 * and the sky jumps once per level — a bug that is obvious on a phone and
 * invisible in a diff.
 *
 * All of this is deliberately pure and free of Compose types, for the reason the
 * rest of this project keeps its logic pure: the renderer cannot be run here, but
 * arithmetic can be, and this is the part where a sign error costs an hour on
 * device.
 */
object UniverseMath {

    /**
     * How much bigger a shell is than the one nested inside it.
     *
     * This is the single number that sets the *feel* of the dive. Too small and
     * a level is over before it registers; too large and you stare at empty space
     * between structures. 3.4 puts roughly one full structure on screen at a time
     * with its child already visible as a bright knot at the centre — which is
     * what makes the next level feel like a place you are going rather than a
     * screen that appears.
     */
    const val SCALE = 3.4f

    /**
     * Which shells are drawn, relative to the current depth. `-1` is the parent
     * you are leaving, `0` is where you are, `1` and `2` are nested inside.
     *
     * Two below rather than one because at [SCALE] 3.4 the grandchild is still
     * about 9% of the screen — big enough that its absence reads as the centre
     * being empty right up until it pops in.
     */
    val SHELLS = -1..2

    /** Which shell of the ladder you are inside. */
    fun depthOf(zoom: Float): Int = floor(zoom).toInt()

    /** How far through the current shell, `0f..1f`. */
    fun fractionOf(zoom: Float): Float = zoom - floor(zoom)

    /**
     * Where shell [j] sits, in powers of [SCALE]. Positive is near and large,
     * negative is far and small, and `0f` is the shell filling the screen.
     */
    fun levelOf(j: Int, fraction: Float): Float = fraction - j

    /** Shell size as a multiple of the base radius. */
    fun shellScale(level: Float): Float = SCALE.pow(level)

    /**
     * Shell content is generated from this, and it depends only on absolute
     * depth — never on the current zoom. That is what makes a dive reversible:
     * pinch back out and you arrive in the same system you left, rather than a
     * freshly rolled one.
     */
    fun seedFor(depth: Int, j: Int): Int = seedFor(NO_BRANCH, depth, j)

    /**
     * The same, inside a chosen dimension.
     *
     * A dive begins by picking a star, and every star has to lead somewhere
     * genuinely different — otherwise the choice is decoration and the second
     * star you try tells you so. Mixing the branch into the seed by a large odd
     * multiplier puts each dimension in a region of the hash far from its
     * neighbours, so two stars share no structure at any depth. It is still only
     * arithmetic: nothing about a dimension is stored, and re-entering the same
     * star rebuilds the same universe.
     */
    fun seedFor(branch: Int, depth: Int, j: Int): Int = branch * 1_000_003 + depth + j

    /** The branch used before a star is chosen, and by anything not in a dimension. */
    const val NO_BRANCH = 0

    /**
     * Fade in from far away, fade out on the way past the camera.
     *
     * Both ends must reach exactly zero *inside* the drawn range, or a shell
     * appears or vanishes at full brightness the moment it enters [SHELLS] —
     * which is the pop this whole scheme exists to avoid.
     */
    fun shellAlpha(level: Float): Float = when {
        level >= NEAR_GONE -> 0f
        level > NEAR_FADE -> (NEAR_GONE - level) / (NEAR_GONE - NEAR_FADE)
        level >= FAR_FADE -> 1f
        level > FAR_GONE -> (level - FAR_GONE) / (FAR_FADE - FAR_GONE)
        else -> 0f
    }

    /**
     * How brightly a shell's own core burns.
     *
     * This is the trick that makes a dive feel like travel rather than like a
     * scale animation. A distant shell shows a hot point at its centre — that is
     * what you are aiming at. As you arrive, that point has to get out of the
     * way, because the thing it was standing in for is the child shell now
     * unfolding in the same spot. So the core fades out exactly as its own
     * structure grows past the screen.
     *
     * Skip this and every level is drawn with a brilliant dot pinned over the
     * middle of the next one, which buries the very thing you flew in to see.
     */
    fun coreGlow(level: Float): Float = when {
        level <= CORE_FULL -> 1f
        level >= CORE_OUT -> 0f
        else -> 1f - (level - CORE_FULL) / (CORE_OUT - CORE_FULL)
    }

    /**
     * What to call this depth.
     *
     * The names are the only thing that tells you the dive is going somewhere —
     * the geometry is self-similar by construction, so without a label a descent
     * of ten levels and a descent of one look alike. They cycle rather than run
     * out, with a lap number after the first pass, because the zoom does not end
     * and a list of names would.
     */
    fun labelFor(depth: Int): String {
        if (depth <= 0) return TIERS[0]
        val lap = depth / TIERS.size
        val name = TIERS[depth % TIERS.size]
        return if (lap == 0) name else "$name ${lap + 1}"
    }

    /**
     * What kind of structure a shell is. Same seed and trait, same kind, always.
     *
     * Drawn from the DIMENSION's shapes rather than from all four, weighted so
     * the first is twice as likely as the rest — a red dwarf's sky should be
     * mostly swarms, not swarms exactly half the time.
     */
    @JvmOverloads
    fun kindFor(seed: Int, trait: DimensionTrait = DimensionTrait.NEUTRAL): ShellKind {
        val shapes = if (trait.shapes.isEmpty()) ShellKind.entries.toList() else trait.shapes
        val roll = OrbMath.unitRandom(seed * 31 + 7)
        if (shapes.size == 1) return shapes[0]
        if (roll < 0.5f) return shapes[0]
        val rest = ((roll - 0.5f) / 0.5f * (shapes.size - 1)).toInt().coerceAtMost(shapes.size - 2)
        return shapes[rest + 1]
    }

    /**
     * The structure at [seed], generated rather than stored.
     *
     * Every number here comes from [OrbMath.unitRandom], which is a hash and not
     * a generator: it has no state, so the tenth shell can be built without
     * building the nine before it, and flying back up rebuilds exactly what was
     * there. A `Random` seeded per shell would also be repeatable, but only if
     * every call site drew the same values in the same order — a constraint that
     * survives about one refactor.
     */
    @JvmOverloads
    fun shellAt(seed: Int, trait: DimensionTrait = DimensionTrait.NEUTRAL): ShellSpec {
        val kind = kindFor(seed, trait)
        // The dimension sets the range; the seed picks inside it. That order is
        // the whole fix for "all the dimensions look the same": branching the
        // seed only ever changed which number came out of an identical range.
        val span = trait.bodies.last - trait.bodies.first + 1
        val count = trait.bodies.first + (OrbMath.unitRandom(seed * 17 + 3) * span).toInt()
            .coerceAtMost(span - 1)
        val satellites = (0 until count).map { i ->
            val s = seed * 101 + i * 13
            Satellite(
                // Nothing inside 0.34: that band belongs to the child shell, and
                // a satellite there would be read as part of it.
                orbit = OrbMath.range(s + 1, 0.34f, 1.02f) * trait.reach,
                size = OrbMath.range(s + 2, 0.030f, 0.075f),
                phase = OrbMath.unitRandom(s + 3) * OrbMath.TAU,
                // Signed, so some satellites run backwards and the system never
                // reads as one rigid turntable — the same choice Orbit's rings make.
                speed = OrbMath.range(s + 4, 0.20f, 0.85f) * trait.tempo *
                    (if (OrbMath.unitRandom(s + 5) > 0.5f) 1f else -1f),
                tilt = OrbMath.range(s + 6, -0.9f, 0.9f),
                warmth = OrbMath.unitRandom(s + 7),
            )
        }
        return ShellSpec(
            seed = seed,
            kind = kind,
            designation = designationFor(seed),
            coreSize = OrbMath.range(seed * 41 + 5, 0.10f, 0.17f),
            satellites = satellites,
            // Decided here rather than in the renderer, so the arm count is part
            // of what a shell IS and can be named in the readout. It was derived
            // from armTwist inside the draw loop, where nothing else could see it.
            arms = 2 + (OrbMath.unitRandom(seed * 83 + 19) * 3).toInt(),
            haze = OrbMath.range(seed * 97 + 23, 0.35f, 1f) * trait.density,
            lanes = when (kind) {
                ShellKind.Spiral -> 2 + (OrbMath.unitRandom(seed * 29 + 31) * 3).toInt()
                ShellKind.Ringed -> 3
                ShellKind.Cluster -> 0
                ShellKind.Binary -> 1
            },
            dust = (
                when (kind) {
                    ShellKind.Spiral -> 220
                    ShellKind.Cluster -> 170
                    ShellKind.Ringed -> 110
                    ShellKind.Binary -> 90
                } * trait.density
                ).toInt(),
            armTwist = OrbMath.range(seed * 59 + 11, 1.6f, 3.4f) *
                (if (OrbMath.unitRandom(seed * 59 + 12) > 0.5f) 1f else -1f),
            tilt = OrbMath.range(seed * 71 + 13, -0.7f, 0.7f),
        )
    }

    /**
     * A catalogue name for the structure at [seed].
     *
     * "more descriptive" was the note, and this is most of the answer. The
     * geometry is self-similar by construction, so a shell twelve levels down and
     * a shell one level down are the same KIND of thing seen at different scales
     * — which means the only way a descent feels like it is going somewhere is if
     * each place is named. A name also makes the dive verifiable by eye: fly down
     * and back up, and the same designation should come back.
     *
     * Two letters and four digits, both from the seed, so it is stable forever
     * and needs nothing stored.
     */
    fun designationFor(seed: Int): String {
        val a = LETTERS[(OrbMath.unitRandom(seed * 13 + 41) * LETTERS.length).toInt()
            .coerceAtMost(LETTERS.length - 1)]
        val b = LETTERS[(OrbMath.unitRandom(seed * 53 + 7) * LETTERS.length).toInt()
            .coerceAtMost(LETTERS.length - 1)]
        val n = (OrbMath.unitRandom(seed * 61 + 11) * 9000f).toInt() + 1000
        return "$a$b-$n"
    }

    /**
     * What this place is, in one line: its shape, what it holds, and how it is
     * built. Shown under the tier name while you are inside it.
     */
    fun describe(spec: ShellSpec): String {
        val bodies = "${spec.satellites.size} " + if (spec.satellites.size == 1) "BODY" else "BODIES"
        val shape = when (spec.kind) {
            ShellKind.Spiral -> "${spec.arms}-ARM SPIRAL"
            ShellKind.Ringed -> "RINGED DISC"
            ShellKind.Cluster -> "GLOBULAR SWARM"
            ShellKind.Binary -> "BINARY PAIR"
        }
        return "$shape · $bodies"
    }

    /**
     * Which star a touch at [x], [y] (in frame fractions) lands on, or null.
     *
     * The radius is generous and deliberately larger than the drawn star: these
     * are small bright points on a dark screen and a finger is not, so hit-testing
     * to the visible size would make half the map feel broken. Nearest-wins rather
     * than first-match, so a touch between two neighbours goes to the closer one
     * instead of to whichever happens to come first in the list.
     */
    fun starAt(stars: List<StarSpec>, x: Float, y: Float): StarSpec? {
        var best: StarSpec? = null
        var bestDistance = STAR_TOUCH_RADIUS
        stars.forEach { star ->
            val dx = star.x - x
            val dy = (star.y - y) * ASPECT
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < bestDistance) {
                bestDistance = d
                best = star
            }
        }
        return best
    }

    /** True once the pinch has been dragged back past the top of the ladder. */
    fun shouldClose(zoom: Float): Boolean = zoom <= CLOSE_AT

    /** Keeps a shove from throwing the structure off screen entirely. */
    fun clampPan(value: Float, limit: Float): Float =
        if (abs(value) <= limit) value else if (value > 0f) limit else -limit

    /**
     * How far the eye may be moved off centre at a given zoom.
     *
     * **Zero at rest, and that is the point.** The pan limit used to be a flat
     * 0.45 of the frame whatever the zoom, so a galaxy that fitted the screen
     * perfectly could still be shoved into a corner — *"just that I'm able to
     * displace this"*. Nothing is gained by dragging a picture that already fits,
     * and every drag that does it leaves the composition worse than it was found.
     *
     * Panning is for looking around something too big to see at once, so the
     * allowance is exactly the overhang: at [view] 1 the content fits and the
     * limit is 0, and every bit of zoom past that buys the same bit of travel.
     * This is how a photo viewer behaves, and it is why one never leaves a photo
     * stranded off screen.
     *
     * @param span the shorter side of the frame.
     */
    /**
     * Where a star sitting at normalised ([x], [y]) lands on screen.
     *
     * **The stars were not attached to their galaxy.** The galaxy body scaled with
     * the zoom and panned with the drag; the star map drew every star at
     * `x * width, y * height` and took neither. So pinching pulled the galaxy out
     * from under its own stars — *"i can zoom the center but the stars don't
     * zoom"*, *"shouldn't it be attached?"* — and the hit test, which did the same
     * bare division in reverse, stopped agreeing with what was on screen the
     * moment anything moved. Zoom in and the stars became unreachable.
     *
     * One transform, here, used by the drawing AND by the tap, is the only way the
     * two cannot drift apart. Returned as a plain pair because this file is
     * Compose-free on purpose and `Offset` is not.
     *
     * @return screen x to screen y.
     */
    fun onScreen(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        panX: Float,
        panY: Float,
        view: Float,
    ): Pair<Float, Float> {
        val cx = w / 2f
        val cy = h / 2f
        return (cx + panX + (x * w - cx) * view) to (cy + panY + (y * h - cy) * view)
    }

    /**
     * The inverse of [onScreen]: which star position a screen point corresponds to.
     *
     * Guarded against a zero [view] because a division by it would put every tap
     * at infinity and silently stop hit testing rather than crash — the worst kind
     * of failure, and one this file has already produced once.
     */
    fun fromScreen(
        sx: Float,
        sy: Float,
        w: Float,
        h: Float,
        panX: Float,
        panY: Float,
        view: Float,
    ): Pair<Float, Float> {
        val v = if (abs(view) < 1e-4f) 1e-4f else view
        val cx = w / 2f
        val cy = h / 2f
        return ((cx + (sx - cx - panX) / v) / w) to ((cy + (sy - cy - panY) / v) / h)
    }

    fun panLimit(span: Float, view: Float): Float =
        span * 0.5f * (view - 1f).coerceAtLeast(0f)

    // ── Zoom is the only way in ──────────────────────────────────────────
    //
    // Tapping is gone. A galaxy, a star, a world are all entered the same
    // way now: you put your fingers on the thing and pinch it larger until
    // you fall into it, and pinch back out to leave. Two pieces of that are
    // arithmetic and belong here, away from the renderer the device is the
    // first ever to compile — a sign error in either is an hour on a phone.

    /** The floor of a stage's zoom. Below it, a pinch-out leaves the stage. */
    const val MIN_VIEW = 0.55f

    /**
     * The ceiling of a stage's zoom, and the trigger to enter. A structure
     * pinched to this size fills enough of the screen that falling into it is
     * seamless — and nothing is allowed past it, so a pinch over empty space
     * simply stops rather than diving into nothing.
     */
    const val ENTER_VIEW = 5.5f

    /** The view scale after one pinch step, held inside a stage's range. */
    fun zoomView(view: Float, gestureZoom: Float): Float =
        (view * gestureZoom).coerceIn(MIN_VIEW, ENTER_VIEW)

    /**
     * The new pan that keeps the focal point of a pinch fixed on screen.
     *
     * "Pinch directly on it" only works if the point between the two fingers
     * does not move while everything around it grows. The content is drawn as
     * `center + pan + (p - center) * view` (see [onScreen]); holding a screen
     * point [focus] still while the view scales by [k] forces exactly this pan.
     * Derived once here so the drawing and the gesture cannot drift apart — the
     * same reason [onScreen] and [fromScreen] are a pair.
     *
     * [k] is the REALISED scale, `newView / oldView` after [zoomView] clamps it,
     * not the raw gesture zoom: when a pinch is capped at [ENTER_VIEW] the pan
     * has to use the capped ratio or the focal point slides on the last frame.
     * One axis at a time; call it for x and again for y.
     *
     * @param center half the frame on this axis — the pivot the view scales about.
     */
    fun focalPan(pan: Float, focus: Float, center: Float, k: Float): Float =
        pan * k + (focus - center) * (1f - k)

    /**
     * Whether this pinch step should ENTER the structure under the fingers.
     *
     * Only when zooming IN ([gestureZoom] > 1) and the view has reached the top
     * of the stage. The direction guard matters: a view already at the ceiling
     * must not enter on the next drag or the first pinch-out — only on a genuine
     * push further in. What is actually entered is decided by the renderer, from
     * whatever structure is nearest the focal point at this instant; if that is
     * nothing, the view just holds at [ENTER_VIEW].
     */
    fun shouldEnter(view: Float, gestureZoom: Float): Boolean =
        gestureZoom > 1f && view >= ENTER_VIEW - 1e-3f

    /** Where a fresh dive starts: shell 0, filling the screen. */
    const val START_ZOOM = 0f

    /** Pinching back out past this closes the view rather than showing nothing. */
    const val CLOSE_AT = -0.45f

    /**
     * Where the camera starts when the view opens, before flying in to
     * [START_ZOOM].
     *
     * The arrival is a movement rather than an appearance: starting outside the
     * first shell and rushing inward means the first thing that happens after the
     * pinch is a structure coming up to meet you. It MUST sit above [CLOSE_AT] —
     * a view that opens past its own dismissal threshold would close itself on
     * the first frame, which `UniverseMathTest` pins.
     */
    const val ENTRY_ZOOM = -0.28f

    // The fade window, in powers of SCALE. Asymmetric on purpose: a shell on its
    // way past the camera is enormous and its edges have long left the screen, so
    // it can hold full brightness later than a distant one can.
    //
    // Both ends stop SHORT of the extremes the range can reach (-2 and +2), and
    // that slack is load-bearing rather than tidy. The first version ended the
    // far fade at exactly -2.0, and `UniverseMathTest` caught what that does: an
    // instant after a seam the innermost shell is at level -1.9995 and therefore
    // 0.1% visible, while an instant before, its counterpart was not drawn at
    // all. Invisible in itself — but it means the drawn set differs either side
    // of every level boundary, which is the exact property the whole scheme
    // rests on. A shell must be fully dark for a moment before it is allowed to
    // arrive, and after it has gone.
    // Where a core hands over to the child structure standing in the same place.
    // It must be fully out BEFORE the shell fills the screen, not as it does: by
    // then the child is already a third of the width and showing its own arms,
    // and a bright dot pinned over the middle of that is the one thing the dive
    // cannot afford. The first draft finished the handover at level 0 and left
    // 15% of the glow still burning there — small enough to look intentional,
    // and exactly the sort of thing that is easier to catch in arithmetic than
    // on a screen.
    private const val CORE_FULL = -1.60f
    private const val CORE_OUT = -0.15f

    private const val NEAR_GONE = 1.72f
    private const val NEAR_FADE = 1.00f
    private const val FAR_FADE = -1.55f
    private const val FAR_GONE = -1.90f

    /** Closest two stars may sit, in frame fractions. Below this they overlap. */
    const val MIN_STAR_GAP = 0.19f

    /** How near a touch must be to count. Larger than the drawn star, on purpose. */
    const val STAR_TOUCH_RADIUS = 0.12f

    /**
     * Roughly a phone's height over its width. The map is laid out in FRACTIONS
     * of the frame, so a gap of 0.19 across is a much bigger gap than 0.19 down —
     * without this correction the relaxation spaces stars generously left-to-right
     * and leaves them touching vertically.
     */
    private const val ASPECT = 2.0f

    /** No I or O: they read as 1 and 0 in a catalogue number. */
    private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"

    private val TIERS = listOf(
        "GALAXY", "CLUSTER", "SYSTEM", "WORLD", "MOON",
        "SURFACE", "GRAIN", "LATTICE", "MOLECULE", "ATOM", "QUANTUM",
    )
}

/** What a shell is made of. Four shapes, so a dive does not repeat itself. */
enum class ShellKind {
    /** Arms of dust winding out of a bright hub. */
    Spiral,

    /** A wide flat disc of debris, seen at a tilt. */
    Ringed,

    /** No structure at all — a loose swarm, the way a globular cluster looks. */
    Cluster,

    /** Two bodies about a common centre, everything else swept out. */
    Binary,
}

/** One body going round the centre of a shell. */
data class Satellite(
    /** Fraction of the shell's radius. */
    val orbit: Float,
    val size: Float,
    val phase: Float,
    /** Signed: negative runs the other way. */
    val speed: Float,
    val tilt: Float,
    /** 0 = accent, 1 = highlight. */
    val warmth: Float,
)

/** Everything one shell needs to be drawn. */
data class ShellSpec(
    /**
     * Carried rather than looked up, because the renderer needs it too: the dust
     * in a shell is placed by hashing this with the mote's index. Without it,
     * every shell scatters its dust identically — and since four are on screen at
     * once, that is the same galaxy repeated at four sizes, which is the exact
     * thing self-similar structure has to avoid looking like.
     */
    val seed: Int,
    val kind: ShellKind,
    /** Catalogue name, shown in the readout — see [UniverseMath.designationFor]. */
    val designation: String,
    val coreSize: Float,
    val satellites: List<Satellite>,
    val dust: Int,
    val armTwist: Float,
    val tilt: Float,
    /** How many arms a spiral winds out of its hub. */
    val arms: Int,
    /** How much gas this structure sits in, 0..1. */
    val haze: Float,
    /** Dark dust lanes cutting across the structure. */
    val lanes: Int,
)

/**
 * A kind of star on the opening map.
 *
 * Nine stars, six kinds, each drawn differently and each described differently —
 * the point of a chooser is that the options are visibly not the same, and a map
 * of nine identical dots with different names would be a list pretending to be a
 * sky.
 */
enum class StarKind(
    val label: String,
    /** What you are told about it before you commit to going there. */
    val summary: String,
    /** 0 = cool and toward the accent, 1 = hot and toward the highlight. */
    val heat: Float,
    /** How large it draws on the chart, relative to the others. */
    val scale: Float,
    // ── What its dimension is LIKE ──────────────────────────────────────────
    //
    // Added because branching the seed was not enough. Nine stars did lead to
    // nine different sets of random numbers — and they all looked the same,
    // because a spiral is a spiral whatever numbers built it. "I asked for each
    // star's dimensions to be different" is a request about CHARACTER, and
    // character comes from the ranges, not from the rolls inside them.
    /** Which structures form here. The first is the common one. */
    val shapes: List<ShellKind>,
    /** How much dust and gas. 1 is the neutral amount. */
    val density: Float,
    /** How many bodies orbit each structure. */
    val bodies: IntRange,
    /** How fast everything moves. */
    val tempo: Float,
    /** How far out the structures reach, as a multiple of the shell. */
    val reach: Float,
) {
    BlueGiant(
        "BLUE GIANT", "Enormous, short-lived, and far too bright for what surrounds it.",
        heat = 1f, scale = 1.6f,
        // Vast and nearly empty: a few enormous structures, little left over.
        shapes = listOf(ShellKind.Spiral, ShellKind.Ringed),
        density = 0.55f, bodies = 2..4, tempo = 1.35f, reach = 1.15f,
    ),
    RedDwarf(
        "RED DWARF", "Small, cool and patient. The most common thing in any sky.",
        heat = 0f, scale = 0.75f,
        // The opposite: crowded, close-packed, and slow.
        shapes = listOf(ShellKind.Cluster, ShellKind.Binary),
        density = 1.5f, bodies = 6..10, tempo = 0.55f, reach = 0.72f,
    ),
    Binary(
        "BINARY", "Two suns locked around a shared centre, each pulling on the other.",
        heat = 0.6f, scale = 1.15f,
        // Everything here comes in twos.
        shapes = listOf(ShellKind.Binary, ShellKind.Ringed),
        density = 0.9f, bodies = 2..4, tempo = 1f, reach = 0.95f,
    ),
    Pulsar(
        "PULSAR", "A collapsed core sweeping a beam past you, several times a second.",
        heat = 0.85f, scale = 0.9f,
        // High energy and tightly wound: discs and rings, turning fast.
        shapes = listOf(ShellKind.Ringed, ShellKind.Spiral),
        density = 0.8f, bodies = 3..5, tempo = 2.1f, reach = 0.88f,
    ),
    Protostar(
        "PROTOSTAR", "Still gathering. Wrapped in the cloud it is condensing out of.",
        heat = 0.35f, scale = 1.25f,
        // Mostly gas, barely any of it formed into anything yet.
        shapes = listOf(ShellKind.Cluster, ShellKind.Spiral),
        density = 2.1f, bodies = 1..3, tempo = 0.7f, reach = 1.05f,
    ),
    WhiteDwarf(
        "WHITE DWARF", "What is left when a star has finished. Dense, dim, and cooling.",
        heat = 0.95f, scale = 0.6f,
        // Swept clean. The emptiest of the six.
        shapes = listOf(ShellKind.Binary, ShellKind.Cluster),
        density = 0.35f, bodies = 2..3, tempo = 0.4f, reach = 0.65f,
    ),
    ;
}

/** What a place is like, independent of which star leads there. */
data class DimensionTrait(
    val shapes: List<ShellKind>,
    val density: Float,
    val bodies: IntRange,
    val tempo: Float,
    val reach: Float,
) {
    companion object {
        /** Everything at 1: the behaviour before dimensions had character. */
        val NEUTRAL = DimensionTrait(ShellKind.entries.toList(), 1f, 2..9, 1f, 1f)
    }
}

/** The character of the dimension behind [kind], or the neutral one for null. */
fun traitOf(kind: StarKind?): DimensionTrait = if (kind == null) {
    DimensionTrait.NEUTRAL
} else {
    DimensionTrait(kind.shapes, kind.density, kind.bodies, kind.tempo, kind.reach)
}

/** One star on the opening map, and the dimension behind it. */
data class StarSpec(
    /**
     * The dimension this star leads to. Never zero — [UniverseMath.NO_BRANCH] is
     * reserved for "no star chosen", so a branch of 0 would make the first star
     * indistinguishable from having chosen nothing.
     */
    val branch: Int,
    val kind: StarKind,
    /** Position as a fraction of the frame. */
    val x: Float,
    val y: Float,
    val size: Float,
    val phase: Float,
    val designation: String,
)
