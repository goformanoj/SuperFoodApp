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
    fun seedFor(depth: Int, j: Int): Int = depth + j

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

    /** What kind of structure a shell is. Same seed, same kind, always. */
    fun kindFor(seed: Int): ShellKind =
        ShellKind.entries[(OrbMath.unitRandom(seed * 31 + 7) * ShellKind.entries.size)
            .toInt()
            .coerceAtMost(ShellKind.entries.size - 1)]

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
    fun shellAt(seed: Int): ShellSpec {
        val kind = kindFor(seed)
        val count = when (kind) {
            ShellKind.Binary -> 2
            ShellKind.Cluster -> 6 + (OrbMath.unitRandom(seed * 17 + 3) * 4).toInt()
            ShellKind.Ringed -> 3 + (OrbMath.unitRandom(seed * 17 + 3) * 2).toInt()
            ShellKind.Spiral -> 4 + (OrbMath.unitRandom(seed * 17 + 3) * 3).toInt()
        }
        val satellites = (0 until count).map { i ->
            val s = seed * 101 + i * 13
            Satellite(
                // Nothing inside 0.34: that band belongs to the child shell, and
                // a satellite there would be read as part of it.
                orbit = OrbMath.range(s + 1, 0.34f, 1.02f),
                size = OrbMath.range(s + 2, 0.030f, 0.075f),
                phase = OrbMath.unitRandom(s + 3) * OrbMath.TAU,
                // Signed, so some satellites run backwards and the system never
                // reads as one rigid turntable — the same choice Orbit's rings make.
                speed = OrbMath.range(s + 4, 0.20f, 0.85f) *
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
            haze = OrbMath.range(seed * 97 + 23, 0.35f, 1f),
            lanes = when (kind) {
                ShellKind.Spiral -> 2 + (OrbMath.unitRandom(seed * 29 + 31) * 3).toInt()
                ShellKind.Ringed -> 3
                ShellKind.Cluster -> 0
                ShellKind.Binary -> 1
            },
            dust = when (kind) {
                ShellKind.Spiral -> 220
                ShellKind.Cluster -> 170
                ShellKind.Ringed -> 110
                ShellKind.Binary -> 90
            },
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

    /** True once the pinch has been dragged back past the top of the ladder. */
    fun shouldClose(zoom: Float): Boolean = zoom <= CLOSE_AT

    /** Keeps a shove from throwing the structure off screen entirely. */
    fun clampPan(value: Float, limit: Float): Float =
        if (abs(value) <= limit) value else if (value > 0f) limit else -limit

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
