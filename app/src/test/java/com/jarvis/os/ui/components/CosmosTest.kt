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
