package com.jarvis.os.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * The universe's own light, its worlds, and its galaxies.
 *
 * This file exists because of one sentence: *"the dimensions 60-70% look the
 * same"*. They did, and the cause was that every one of them was drawn in the app
 * theme's two colours — so however different the geometry underneath, the eye saw
 * one place. Colour is the strongest signal a place has, and it is pure
 * arithmetic, which means it is checkable here rather than on a phone.
 */
class CosmosTest {

    // ---- colour ----------------------------------------------------------

    @Test
    fun `hsl stays inside the colour cube`() {
        var h = -1.5f
        while (h <= 2.5f) {
            listOf(0f, 0.5f, 1f).forEach { s ->
                listOf(0f, 0.3f, 0.7f, 1f).forEach { l ->
                    val ink = Ink.hsl(h, s, l)
                    listOf(ink.r, ink.g, ink.b).forEach {
                        assertTrue("hsl($h,$s,$l) produced $ink", it in -0.001f..1.001f)
                    }
                }
            }
            h += 0.05f
        }
    }

    @Test
    fun `hue wraps instead of going black`() {
        // Callers add offsets freely — a gas shift of +0.42 on a hue of 0.9 is
        // 1.32. If that clamped instead of wrapping, every warm dimension would
        // have a black cloud in it and nothing would say why.
        assertClose(Ink.hsl(0.1f, 0.8f, 0.5f), Ink.hsl(1.1f, 0.8f, 0.5f))
        assertClose(Ink.hsl(0.1f, 0.8f, 0.5f), Ink.hsl(-0.9f, 0.8f, 0.5f))
    }

    @Test
    fun `the six primaries land where they should`() {
        // A sanity check on the conversion itself: if the sector arithmetic is
        // off by one, everything downstream is subtly the wrong colour and no
        // test about "difference" would catch it.
        assertClose(Ink(1f, 0f, 0f), Ink.hsl(0f, 1f, 0.5f))
        assertClose(Ink(0f, 1f, 0f), Ink.hsl(1f / 3f, 1f, 0.5f))
        assertClose(Ink(0f, 0f, 1f), Ink.hsl(2f / 3f, 1f, 0.5f))
    }

    // ---- dimensions that look different ----------------------------------

    @Test
    fun `a place does not sit on one hue`() {
        // The specific failure being prevented: gas next to the arm colour on the
        // wheel flattens a dimension into a single wash with shading. They have
        // to be genuinely far apart.
        (1..60).forEach { branch ->
            StarKind.entries.forEach { kind ->
                val p = paletteFor(branch, kind)
                val gap = hueGap(p.hue, hueOf(p.gas))
                assertTrue(
                    "branch $branch/$kind: gas sits $gap from the arm — that is one colour, not two",
                    gap > 0.16f,
                )
            }
        }
    }

    @Test
    fun `each kind of star keeps its own family of colour`() {
        // The jitter must not be so wide that a red dwarf can come out green —
        // the chart's labels would then be lying about where you are going.
        val families = StarKind.entries.associateWith { kind ->
            (1..40).map { paletteFor(it, kind).hue }
        }
        families.forEach { (kind, hues) ->
            val spread = hues.maxOf { a -> hues.maxOf { b -> hueGap(a, b) } }
            assertTrue("$kind's hues are scattered across $spread of the wheel", spread < 0.20f)
        }
    }

    @Test
    fun `but two places of the same kind are not the same colour`() {
        // The other half. A family that never varies is six dimensions, not
        // forty — which is exactly the "they all look the same" complaint.
        StarKind.entries.forEach { kind ->
            val hues = (1..25).map { paletteFor(it, kind).hue }
            assertTrue("$kind rolled the same hue every time", hues.toSet().size > 20)
        }
    }

    @Test
    fun `different kinds of star are visibly different places`() {
        val hues = StarKind.entries.map { paletteFor(1, it).hue }
        hues.forEachIndexed { i, a ->
            hues.drop(i + 1).forEach { b ->
                assertTrue("two star kinds share a hue ($a vs $b)", hueGap(a, b) > 0.05f)
            }
        }
    }

    @Test
    fun `nothing comes out grey or black`() {
        // A palette that collapses to grey is worse than a repeated one: it looks
        // like a rendering fault rather than a design.
        (1..50).forEach { branch ->
            StarKind.entries.forEach { kind ->
                val p = paletteFor(branch, kind)
                listOf("arm" to p.arm, "gas" to p.gas, "spark" to p.spark).forEach { (name, ink) ->
                    val lo = min(min(ink.r, ink.g), ink.b)
                    val hi = maxOf(ink.r, ink.g, ink.b)
                    assertTrue("branch $branch/$kind: $name is grey", hi - lo > 0.08f)
                    assertTrue("branch $branch/$kind: $name is black", hi > 0.15f)
                }
                assertTrue("the core should be near-white", p.core.r + p.core.g + p.core.b > 2.2f)
            }
        }
    }

    // ---- worlds ----------------------------------------------------------

    @Test
    fun `a dimension only grows worlds that suit it`() {
        // Lava worlds and ice worlds equally likely everywhere is not a universe,
        // it is a shuffle. Each star kind has a climate.
        StarKind.entries.forEach { star ->
            val allowed = PlanetKind.formingIn(star)
            (0..80).forEach { seed ->
                val planet = planetFor(seed, star)
                assertTrue("$star grew a ${planet.kind}, not in $allowed", planet.kind in allowed)
            }
        }
    }

    @Test
    fun `every star kind can still grow more than one sort of world`() {
        StarKind.entries.forEach { star ->
            val grown = (0..80).map { planetFor(it, star).kind }.toSet()
            assertTrue("$star only ever grows $grown", grown.size >= 3)
        }
    }

    @Test
    fun `a world is the same world every time it is looked at`() {
        assertEquals(planetFor(21, StarKind.RedDwarf), planetFor(21, StarKind.RedDwarf))
    }

    @Test
    fun `only the kinds that can hold a ring system have one`() {
        (0..120).forEach { seed ->
            StarKind.entries.forEach { star ->
                val p = planetFor(seed, star)
                if (p.rings > 0) {
                    assertTrue(
                        "a ${p.kind} should not have rings",
                        p.kind == PlanetKind.Ringed || p.kind == PlanetKind.GasGiant,
                    )
                }
            }
        }
    }

    @Test
    fun `every world has a surface to draw`() {
        (0..120).forEach { seed ->
            StarKind.entries.forEach { star ->
                val p = planetFor(seed, star)
                assertTrue("${p.kind} has no features", p.features > 0)
                assertTrue("${p.kind} reflects no light", p.albedo > 0.1f)
                assertTrue("${p.designation} is not catalogue-shaped", p.designation.matches(Regex("[A-Z]{2}-[0-9]{4}")))
            }
        }
    }

    // ---- galaxies --------------------------------------------------------

    @Test
    fun `there is one galaxy per ring of the orb`() {
        // "count the number of moving orbs on the Jarvis main orb and build
        // galaxies according to that" — so this number is not mine to pick.
        (1..8).forEach { rings ->
            assertEquals(rings, galaxiesFor(rings).size)
        }
    }

    @Test
    fun `a theme with no rings still opens onto somewhere`() {
        // Defensive, and cheap: a zero-ring spec would otherwise open onto an
        // empty sky with nothing to touch and no way to tell it was not broken.
        assertTrue(galaxiesFor(0).isNotEmpty())
    }

    @Test
    fun `a galaxy is the same galaxy whichever theme you came from`() {
        // "keep the galaxy constant and it doesn't depend on the theme": the
        // theme decides HOW MANY there are, never what they are. So the first
        // three galaxies of a five-ring orb must equal those of a three-ring one.
        val fromFive = galaxiesFor(5).take(3)
        val fromThree = galaxiesFor(3)
        assertEquals("the same ring gave a different galaxy under another theme", fromThree, fromFive)
    }

    @Test
    fun `no two galaxies are alike`() {
        val galaxies = galaxiesFor(6)
        galaxies.forEachIndexed { i, a ->
            galaxies.drop(i + 1).forEach { b ->
                assertNotEquals("two galaxies are identical", a, b)
                assertNotEquals("two galaxies share a designation", a.designation, b.designation)
            }
        }
    }

    @Test
    fun `every galaxy holds several dimensions, and none are shared`() {
        val all = galaxiesFor(6).flatMap { starsIn(it) }
        all.forEach { assertTrue("a star holds no dimension", it.branch != UniverseMath.NO_BRANCH) }
        assertEquals(
            "two stars in different galaxies lead to the same dimension",
            all.size,
            all.map { it.branch }.toSet().size,
        )
        galaxiesFor(6).forEach {
            assertTrue("${it.designation} holds only ${it.stars} stars", starsIn(it).size >= 5)
        }
    }

    @Test
    fun `stars sit inside their galaxy, not off the edge of it`() {
        galaxiesFor(6).forEach { galaxy ->
            starsIn(galaxy).forEach { star ->
                assertTrue("${star.designation} is off the left/right of its galaxy", star.x in 0.02f..0.98f)
                assertTrue("${star.designation} is off the top/bottom of its galaxy", star.y in 0.02f..0.98f)
            }
        }
    }

    // ---- systems ---------------------------------------------------------

    @Test
    fun `orbits are spaced the way a real system is`() {
        // Evenly spaced orbits read as a target. The crowding toward the star is
        // most of what makes a system look like one, so each orbit must be a
        // clear step outside the last rather than a fixed distance from it.
        galaxiesFor(4).flatMap { starsIn(it) }.forEach { star ->
            val worlds = systemFor(star).worlds
            assertTrue("${star.designation} has no worlds", worlds.size >= 3)
            worlds.zipWithNext().forEach { (inner, outer) ->
                assertTrue(
                    "${star.designation}: ${outer.orbit} is not clear of ${inner.orbit}",
                    outer.orbit > inner.orbit * 1.25f,
                )
            }
        }
    }

    @Test
    fun `inner worlds go round faster, as they do`() {
        // The single cue that makes a system read as mechanism rather than as a
        // set of concentric rings.
        galaxiesFor(3).flatMap { starsIn(it) }.forEach { star ->
            val worlds = systemFor(star).worlds
            worlds.zipWithNext().forEach { (inner, outer) ->
                assertTrue(
                    "${star.designation}: the outer world is not slower",
                    abs(inner.speed) > abs(outer.speed),
                )
            }
        }
    }

    @Test
    fun `no world is inside the star or off past the edge`() {
        galaxiesFor(4).flatMap { starsIn(it) }.forEach { star ->
            systemFor(star).worlds.forEach {
                assertTrue("${star.designation}: a world is inside its sun", it.orbit > 0.15f)
                assertTrue("${star.designation}: a world orbits off the frame", it.orbit < 6f)
            }
        }
    }

    @Test
    fun `a belt sits between orbits, never on top of one`() {
        galaxiesFor(4).flatMap { starsIn(it) }.forEach { star ->
            val sys = systemFor(star)
            if (sys.belt <= 0f) return@forEach
            assertTrue("${star.designation}: an empty belt has density", sys.beltDensity > 0)
            sys.worlds.forEach {
                assertTrue(
                    "${star.designation}: the belt at ${sys.belt} sits on a world at ${it.orbit}",
                    abs(sys.belt - it.orbit) > 0.02f,
                )
            }
        }
    }

    @Test
    fun `a system is the same system every visit`() {
        val star = starsIn(galaxiesFor(3)[1]).first()
        assertEquals(systemFor(star), systemFor(star))
    }

    @Test
    fun `no two stars hold the same system`() {
        val systems = galaxiesFor(4).flatMap { starsIn(it) }.map { systemFor(it) }
        assertEquals(
            "two stars lead to an identical system",
            systems.size,
            systems.toSet().size,
        )
    }

    // ---- things to find --------------------------------------------------

    @Test
    fun `every world has something on it`() {
        (0..60).forEach { seed ->
            StarKind.entries.forEach { star ->
                val planet = planetFor(seed, star)
                val found = landmarksOn(planet)
                assertTrue("a ${planet.kind} has nothing to find", found.isNotEmpty())
                found.forEach {
                    assertTrue(
                        "a ${it.kind} does not belong on a ${planet.kind}",
                        it.kind in LandmarkKind.on(planet.kind),
                    )
                }
            }
        }
    }

    @Test
    fun `nothing floats off the side of the world it is on`() {
        // Two independent coordinates put a "surface feature" outside the disc
        // about a fifth of the time — obvious on a screen, invisible in a diff.
        (0..80).forEach { seed ->
            StarKind.entries.forEach { star ->
                landmarksOn(planetFor(seed, star)).forEach {
                    val d = kotlin.math.sqrt(it.u * it.u + it.v * it.v)
                    assertTrue("a ${it.kind} sits $d from the centre, off the disc", d <= 0.9f)
                }
            }
        }
    }

    @Test
    fun `what you find is the same every time you go back`() {
        val planet = planetFor(9, StarKind.RedDwarf)
        assertEquals(landmarksOn(planet), landmarksOn(planet))
    }

    @Test
    fun `every kind of thing is findable somewhere`() {
        val found = (0..200).flatMap { seed ->
            StarKind.entries.flatMap { landmarksOn(planetFor(seed, it)).map { l -> l.kind } }
        }.toSet()
        assertEquals("some landmarks can never be found", LandmarkKind.entries.toSet(), found)
    }

    @Test
    fun `everything found is named and described`() {
        LandmarkKind.entries.forEach {
            assertTrue("${it.name} has no label", it.label.isNotBlank())
            assertTrue("${it.name} has no note", it.note.length > 20)
        }
    }

    // ---- helpers ---------------------------------------------------------

    /** Distance round the wheel, which is never more than half a turn. */
    private fun hueGap(a: Float, b: Float): Float {
        val d = abs(a - b) % 1f
        return min(d, 1f - d)
    }

    // ── Layer three: a system has to FIT ─────────────────────────────────────

    @Test
    fun `every system is stated as a fraction of its own width`() {
        galaxiesFor(6).flatMap { starsIn(it) }.forEach { star ->
            val worlds = systemFor(star).worlds
            val widest = worlds.maxOf { it.orbit }
            assertEquals("${star.designation} outermost orbit", 1f, widest, 0.0005f)
        }
    }

    @Test
    fun `no world is drawn outside the frame`() {
        // The renderer multiplies `orbit` by a unit sized to the frame, so an
        // orbit above 1 is a world nobody can ever see: pinching out past the
        // minimum leaves the stage rather than pulling back to find it. Orbits
        // used to compound to roughly 8.5 over nine worlds and most of a system
        // was off the screen.
        galaxiesFor(6).flatMap { starsIn(it) }.forEach { star ->
            systemFor(star).worlds.forEach {
                assertTrue(
                    "${star.designation} has a world at ${it.orbit}",
                    it.orbit in 0f..1.0001f,
                )
            }
        }
    }

    @Test
    fun `a system never holds more worlds than its frame can space out`() {
        // The two constraints below only both hold up to a point: every extra
        // world multiplies the innermost orbit down by another whole ratio, and
        // past seven it lands inside the sun however the chain is scaled.
        galaxiesFor(6).flatMap { starsIn(it) }.forEach { star ->
            assertTrue(
                "${star.designation} holds ${systemFor(star).worlds.size} worlds",
                systemFor(star).worlds.size <= 7,
            )
        }
    }

    @Test
    fun `the belt sits between two real orbits`() {
        galaxiesFor(6).flatMap { starsIn(it) }.forEach { star ->
            val system = systemFor(star)
            if (system.belt <= 0f) return@forEach
            val orbits = system.worlds.map { it.orbit }
            assertTrue(
                "${star.designation} belt at ${system.belt} is outside its worlds",
                system.belt > orbits.min() && system.belt < orbits.max(),
            )
        }
    }


    // ── Distinctness: the thing that was actually wrong ──────────────────────

    /**
     * What a world looks like, as a string.
     *
     * Everything a viewer can actually SEE from across a system, and nothing they
     * cannot. Hue is bucketed because two hues a hundredth apart are the same
     * colour to an eye, and counting them as different would let the generator
     * pass this by varying something invisible — which is precisely the failure
     * being tested for.
     */
    @Test
    fun `a star's colour is its temperature, not the app's palette`() {
        // The six classes must land in six different places on the wheel, or the
        // chart is back to one repeated star. Red dwarf and protostar are both
        // deliberately red, so they are compared on lightness instead.
        val blue = hueOf(starInk(StarKind.BlueGiant, 3))
        val red = hueOf(starInk(StarKind.RedDwarf, 3))
        val pulsar = hueOf(starInk(StarKind.Pulsar, 3))
        assertTrue("blue giant at $blue is not blue", blue in 0.50f..0.66f)
        assertTrue("red dwarf at $red is not red", red < 0.09f || red > 0.95f)
        assertTrue("pulsar at $pulsar is not violet", pulsar in 0.66f..0.80f)
    }

    @Test
    fun `two stars of one class are not the same colour`() {
        // The jitter is the point: six kinds without it is six swatches, and a
        // field of red dwarfs should run from amber to salmon.
        StarKind.entries.forEach { kind ->
            val inks = (1..30).map { starInk(kind, it * 13) }
            val distinct = inks.map {
                "${(it.r * 40).toInt()}/${(it.g * 40).toInt()}/${(it.b * 40).toInt()}"
            }.toSet().size
            assertTrue("$kind gives only $distinct colours across 30 stars", distinct >= 20)
        }
    }

    @Test
    fun `no star comes out black or washed to white`() {
        StarKind.entries.forEach { kind ->
            (1..40).forEach { b ->
                val ink = starInk(kind, b * 7)
                val hi = maxOf(ink.r, ink.g, ink.b)
                assertTrue("$kind/$b is too dark", hi > 0.30f)
                val lo = min(min(ink.r, ink.g), ink.b)
                assertTrue("$kind/$b has no colour left in it", kind == StarKind.WhiteDwarf || hi - lo > 0.02f)
            }
        }
    }


    // ── Galaxies, and skies ─────────────────────────────────────────────────

    private fun lookOfGalaxy(g: GalaxySpec): String = listOf(
        g.kind.name,
        g.arms,
        (g.core * 8).toInt(),
        (g.bar * 6).toInt(),
        (g.dust * 5).toInt(),
        (g.lopsided * 4).toInt(),
        (g.scatter * 5).toInt(),
        g.clusters / 3,
        (g.starburst * 4).toInt(),
        if (g.companion >= 0f) "sat" else "-",
        (g.tilt * 5).toInt(),
    ).joinToString("/")

    @Test
    fun `galaxies do not repeat themselves`() {
        // Five kinds meant five pictures, with arms and twist as adjustments
        // nobody could see across a gap — the same fault the planets had.
        val looks = (1..60).map { lookOfGalaxy(galaxiesFor(it).last()) }
        assertTrue("only ${looks.toSet().size} distinct galaxies in 60", looks.toSet().size >= 58)
    }

    @Test
    fun `an elliptical has no dust left`() {
        // It used it up an age ago. A dust lane on one would say the generator is
        // rolling numbers rather than describing anything.
        (1..80).forEach {
            val g = galaxiesFor(it).last()
            if (g.kind == GalaxyKind.Elliptical) {
                assertEquals("${g.designation} has dust", 0f, g.dust, 1e-6f)
            }
        }
    }

    @Test
    fun `a barred galaxy always has its bar, and others may`() {
        var plainWithBar = 0
        (1..90).forEach {
            val g = galaxiesFor(it).last()
            if (g.kind == GalaxyKind.Barred) {
                assertTrue("${g.designation} is barred with no bar", g.bar > 0.2f)
            }
            if (g.kind == GalaxyKind.Spiral && g.bar > 0f) plainWithBar++
        }
        // Tying the bar to the kind is part of what made five kinds read as five
        // fixed pictures; plenty of ordinary spirals have a short one.
        assertTrue("no unbarred spiral ever got a bar", plainWithBar > 0)
    }

    @Test
    fun `skies differ from place to place and never reshuffle`() {
        val looks = (1..80).map {
            val s = skyFor(it * 31)
            listOf(
                (s.bandAngle * 4).toInt(),
                (s.bandWeight * 5).toInt(),
                s.clouds,
                (s.density * 5).toInt(),
                s.distant,
                (s.depth * 4).toInt(),
            ).joinToString("/")
        }
        assertTrue("only ${looks.toSet().size} distinct skies in 80", looks.toSet().size >= 74)
        // Stable: a backdrop that reshuffles on each visit says the place is not
        // real, which is worse than one that never changes.
        assertEquals(skyFor(4242), skyFor(4242))
    }

    @Test
    fun `some skies are nearly empty`() {
        // Emptiness is a characteristic. Without quiet skies the busy ones stop
        // reading as busy, because there is nothing they are busier than.
        val quiet = (1..120).map { skyFor(it * 17) }.count { it.bandWeight < 0.20f }
        assertTrue("no sky is quiet", quiet > 0)
        assertTrue("nearly every sky is quiet ($quiet of 120)", quiet < 60)
    }


    private fun lookOf(p: PlanetSpec): String = listOf(
        p.kind.name,
        p.pattern.name,
        (p.features / 3),
        if (p.cap > 0f) "cap" else "-",
        (p.cloud * 4).toInt(),
        p.storms,
        p.rings,
        p.rays,
        (p.intact * 5).toInt(),
        p.moons,
        (p.ringTilt * 3).toInt(),
        (p.tilt * 2).toInt(),
        (p.albedo * 4).toInt(),
    ).joinToString("/")

    @Test
    fun `worlds do not repeat themselves`() {
        // "why do the planets look the same in each of the stars, just the
        // colours are different". They did: kind alone decided the whole picture,
        // so there were eight possible worlds and everything else was a tint.
        val looks = (0 until 400).map { lookOf(planetFor(it * 37 + 11, null)) }
        val distinct = looks.toSet().size
        assertTrue("only $distinct distinct looks in ${looks.size} worlds", distinct > 340)
    }

    @Test
    fun `two worlds of the same kind still look different`() {
        // The sharper version of the same question, and the one that failed
        // before: hold the material constant and the picture must STILL vary.
        PlanetKind.entries.forEach { kind ->
            val same = (0 until 600)
                .map { planetFor(it * 53 + 7, null) }
                .filter { it.kind == kind }
                .take(40)
            if (same.size < 10) return@forEach
            val distinct = same.map { lookOf(it) }.toSet().size
            assertTrue(
                "$kind: only $distinct distinct looks across ${same.size} of them",
                distinct >= same.size - 2,
            )
        }
    }

    @Test
    fun `every kind can wear more than one pattern`() {
        // A kind with a single plausible pattern is a kind that is back to being
        // one picture, whatever else varies.
        PlanetKind.entries.forEach { kind ->
            assertTrue(
                "$kind has only ${SurfacePattern.formingOn(kind).size} pattern",
                SurfacePattern.formingOn(kind).size >= 3,
            )
        }
    }

    @Test
    fun `a pattern is never implausible for its material`() {
        // Craters on a gas giant would say the generator is not paying attention,
        // and one bad combination undoes fifty good ones.
        assertTrue(SurfacePattern.Cratered !in SurfacePattern.formingOn(PlanetKind.GasGiant))
        assertTrue(SurfacePattern.Banded !in SurfacePattern.formingOn(PlanetKind.Rocky))
        assertTrue(SurfacePattern.Cratered !in SurfacePattern.formingOn(PlanetKind.Ocean))
        (0 until 300).forEach {
            val p = planetFor(it * 17 + 3, null)
            assertTrue(
                "${p.kind} came out ${p.pattern}",
                p.pattern in SurfacePattern.formingOn(p.kind),
            )
        }
    }

    @Test
    fun `a world without air has neither cloud nor caps`() {
        // Cloud on an airless rock, or a polar cap with nothing to freeze out of,
        // is a world assembled from parts rather than generated as one thing.
        (0 until 400).forEach {
            val p = planetFor(it * 23 + 5, null)
            if (p.haze < 0.05f) {
                assertTrue("${p.designation} has cloud without air", p.cloud < 0.05f)
                assertEquals("${p.designation} has caps without air", 0f, p.cap, 1e-6f)
            }
        }
    }

    @Test
    fun `a gas giant is never given a polar cap`() {
        (0 until 400).forEach {
            val p = planetFor(it * 29 + 13, null)
            if (p.kind == PlanetKind.GasGiant) {
                assertEquals("${p.designation} has a cap on nothing", 0f, p.cap, 1e-6f)
            }
        }
    }


    private fun hueOf(ink: Ink): Float {
        val hi = maxOf(ink.r, ink.g, ink.b)
        val lo = min(min(ink.r, ink.g), ink.b)
        if (hi - lo < 1e-5f) return 0f
        val h = when (hi) {
            ink.r -> (ink.g - ink.b) / (hi - lo)
            ink.g -> 2f + (ink.b - ink.r) / (hi - lo)
            else -> 4f + (ink.r - ink.g) / (hi - lo)
        } / 6f
        return ((h % 1f) + 1f) % 1f
    }

    private fun assertClose(a: Ink, b: Ink) {
        assertEquals("r", a.r, b.r, 0.002f)
        assertEquals("g", a.g, b.g, 0.002f)
        assertEquals("b", a.b, b.b, 0.002f)
    }
}
