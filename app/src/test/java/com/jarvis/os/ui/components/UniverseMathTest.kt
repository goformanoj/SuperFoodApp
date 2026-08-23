package com.jarvis.os.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The contract of an endless zoom.
 *
 * "I can keep going deeper and deeper" is a claim about behaviour at the joins,
 * and every way this can fail is arithmetic: the sky jumping once per level, a
 * shell appearing at full brightness, a dive that rolls a different system on the
 * way back up. None of that is visible in a diff and all of it is obvious on a
 * phone, which is the worst possible combination — so it is pinned here, where it
 * runs in seconds against no device at all.
 */
class UniverseMathTest {

    // ---- the seam -------------------------------------------------------

    @Test
    fun `crossing a whole level changes nothing on screen`() {
        // THE property. Just before zoom hits 3.0 the four shells are at some set
        // of sizes, brightnesses and seeds; just after, the counter has ticked and
        // every shell has been relabelled — but the picture must be identical, or
        // there is a visible jolt once per level.
        for (crossing in -1..12) {
            val before = frameAt(crossing - 0.0005f)
            val after = frameAt(crossing + 0.0005f)

            // Shells whose alpha is zero are not drawn, so they are not part of
            // the picture and are allowed to differ at the edges of the range.
            val visibleBefore = before.filter { it.alpha > 0f }
            val visibleAfter = after.filter { it.alpha > 0f }

            assertEquals(
                "level $crossing: a different number of shells is drawn either side of the seam",
                visibleBefore.size,
                visibleAfter.size,
            )
            visibleBefore.sortedBy { it.seed }
                .zip(visibleAfter.sortedBy { it.seed })
                .forEach { (b, a) ->
                    assertEquals("level $crossing: shell identity changes across the seam", b.seed, a.seed)
                    assertClose("level $crossing seed ${b.seed}: size jumps", b.scale, a.scale, 0.01f)
                    assertClose("level $crossing seed ${b.seed}: brightness jumps", b.alpha, a.alpha, 0.01f)
                }
        }
    }

    @Test
    fun `depth and fraction decompose the zoom`() {
        listOf(0f, 0.001f, 0.5f, 0.999f, 1f, 4.25f, 11.75f, -0.3f).forEach { z ->
            val d = UniverseMath.depthOf(z)
            val f = UniverseMath.fractionOf(z)
            assertTrue("fraction $f out of range at zoom $z", f >= 0f && f < 1f)
            assertClose("depth+fraction should rebuild the zoom", z, d + f, 1e-4f)
        }
    }

    // ---- what the dive looks like ---------------------------------------

    @Test
    fun `zooming in one whole level magnifies everything by exactly SCALE`() {
        // This is what makes the seam work, stated the other way round: the child
        // must arrive precisely where its parent was, not approximately.
        val here = UniverseMath.shellScale(UniverseMath.levelOf(0, 0f))
        val child = UniverseMath.shellScale(UniverseMath.levelOf(1, 0f))
        assertClose("a child shell should be one SCALE smaller", here / UniverseMath.SCALE, child, 1e-4f)
    }

    @Test
    fun `shells are drawn nearest-largest, with none the same size`() {
        val scales = UniverseMath.SHELLS.map {
            UniverseMath.shellScale(UniverseMath.levelOf(it, 0.4f))
        }
        assertEquals("two shells occupy the same size", scales.size, scales.toSet().size)
        scales.zipWithNext().forEach { (near, far) ->
            assertTrue("shell order is not monotonic: $scales", near > far)
        }
    }

    @Test
    fun `nothing appears or vanishes while it is bright`() {
        // A shell enters the drawn range at one end and leaves at the other. If
        // alpha is not already zero at both edges, it pops.
        val first = UniverseMath.SHELLS.first
        val last = UniverseMath.SHELLS.last
        // Entering: the innermost shell at the start of a level.
        assertEquals(
            "the innermost shell arrives already visible — it will pop in",
            0f,
            UniverseMath.shellAlpha(UniverseMath.levelOf(last, 0f)),
            1e-4f,
        )
        // Leaving: the outermost shell at the end of a level.
        assertEquals(
            "the outermost shell is still visible when it is recycled — it will pop out",
            0f,
            UniverseMath.shellAlpha(UniverseMath.levelOf(first, 1f)),
            1e-4f,
        )
    }

    @Test
    fun `the shell you are in is always fully lit`() {
        var z = 0f
        while (z < 1f) {
            assertEquals(
                "the current shell dimmed at fraction $z",
                1f,
                UniverseMath.shellAlpha(UniverseMath.levelOf(0, z)),
                1e-4f,
            )
            z += 0.05f
        }
    }

    @Test
    fun `alpha never leaves zero to one`() {
        var level = -3f
        while (level <= 3f) {
            val a = UniverseMath.shellAlpha(level)
            assertTrue("alpha $a out of range at level $level", a in 0f..1f)
            level += 0.01f
        }
    }

    @Test
    fun `a shell's core is out of the way by the time you are inside it`() {
        // The core of the shell you are in stands exactly where the child shell
        // is unfolding. If it is still lit, it is drawn on top of the next level
        // — burying the thing the dive exists to reveal.
        assertEquals(
            "the current shell's core is still burning over its child",
            0f,
            UniverseMath.coreGlow(UniverseMath.levelOf(0, 0f)),
            1e-4f,
        )
        assertEquals(
            "a distant shell has no core to aim at",
            1f,
            UniverseMath.coreGlow(UniverseMath.levelOf(2, 0f)),
            1e-4f,
        )
        // And it must not come back on the way past, or the parent relights over
        // everything as it swells.
        assertEquals(0f, UniverseMath.coreGlow(UniverseMath.levelOf(-1, 0.5f)), 1e-4f)
    }

    @Test
    fun `the core hands over without a step`() {
        var level = -2f
        var previous = UniverseMath.coreGlow(level)
        while (level <= 2f) {
            val now = UniverseMath.coreGlow(level)
            assertTrue("core glow $now out of range at level $level", now in 0f..1f)
            assertTrue("core glow jumps at level $level", abs(now - previous) < 0.05f)
            previous = now
            level += 0.01f
        }
    }

    // ---- the dive is reversible -----------------------------------------

    @Test
    fun `flying back up returns to the same system, not a new one`() {
        // Content comes from absolute depth, so this is true by construction —
        // and it is exactly the thing a "just seed it from the zoom" shortcut
        // would break, silently, in a way that only shows up on the way back.
        val down = UniverseMath.shellAt(UniverseMath.seedFor(depth = 4, j = 1))
        val backUp = UniverseMath.shellAt(UniverseMath.seedFor(depth = 6, j = -1))
        assertEquals("shell 5 should be the same place whichever way you reach it", down, backUp)
    }

    @Test
    fun `a shell is fully determined by its seed`() {
        assertEquals(UniverseMath.shellAt(9), UniverseMath.shellAt(9))
        assertEquals(UniverseMath.kindFor(9), UniverseMath.kindFor(9))
    }

    // ---- the shells actually differ from each other ----------------------

    @Test
    fun `consecutive shells are not the same place twice`() {
        // Self-similar structure is the point; identical structure is a bug. If
        // ten levels in a row produced the same spec the dive would read as a
        // still image being scaled.
        val specs = (0..9).map { UniverseMath.shellAt(it) }
        specs.zipWithNext().forEachIndexed { i, (a, b) ->
            assertNotEquals("shells $i and ${i + 1} are identical", a, b)
        }
        assertTrue(
            "every shell came out the same shape — the dive will not read as going anywhere",
            specs.map { it.kind }.toSet().size > 1,
        )
    }

    @Test
    fun `every kind of shell gets used`() {
        val kinds = (0..200).map { UniverseMath.kindFor(it) }.toSet()
        assertEquals("some shell kinds are unreachable", ShellKind.entries.toSet(), kinds)
    }

    @Test
    fun `no satellite is hidden inside the child shell or lost outside the parent`() {
        // The band below 0.34 belongs to the next level down. Anything orbiting
        // there would be read as part of the child, which is confusing in exactly
        // the moment the dive needs to be legible.
        (0..60).forEach { seed ->
            val spec = UniverseMath.shellAt(seed)
            assertTrue("shell $seed has no satellites", spec.satellites.isNotEmpty())
            spec.satellites.forEach {
                assertTrue("shell $seed: orbit ${it.orbit} intrudes on the child", it.orbit >= 0.34f)
                assertTrue("shell $seed: orbit ${it.orbit} is off screen", it.orbit <= 1.05f)
                assertTrue("shell $seed: a satellite is frozen", abs(it.speed) > 0f)
            }
        }
    }

    @Test
    fun `satellites do not all travel the same way`() {
        // Same reason Orbit's rings alternate: a system where everything turns
        // together reads as one rigid object rather than as bodies in orbit.
        val mixed = (0..40).count { seed ->
            val s = UniverseMath.shellAt(seed).satellites
            s.any { it.speed > 0f } && s.any { it.speed < 0f }
        }
        assertTrue("almost no shell has counter-rotating bodies ($mixed of 41)", mixed > 20)
    }

    // ---- the labels ------------------------------------------------------

    @Test
    fun `the depth is always named, however deep it goes`() {
        val seen = mutableSetOf<String>()
        (0..40).forEach { d ->
            val label = UniverseMath.labelFor(d)
            assertTrue("depth $d has no name", label.isNotBlank())
            assertTrue("depth $d repeats the name of an earlier level: $label", seen.add(label))
        }
    }

    @Test
    fun `the top of the ladder is the galaxy, and stays so while dismissing`() {
        // Zoom goes slightly negative during a dismissing pinch, and a label of
        // "QUANTUM 0" flashing up as the view closes is the kind of detail that
        // makes something feel unfinished.
        assertEquals(UniverseMath.labelFor(0), UniverseMath.labelFor(-1))
        assertEquals(UniverseMath.labelFor(0), UniverseMath.labelFor(UniverseMath.depthOf(UniverseMath.CLOSE_AT)))
    }

    // ---- what a place is called ------------------------------------------

    @Test
    fun `every shell has a name, and it is the same name every time`() {
        (0..80).forEach { seed ->
            val name = UniverseMath.designationFor(seed)
            assertEquals("a designation must be stable", name, UniverseMath.designationFor(seed))
            assertTrue("designation '$name' is not catalogue-shaped", name.matches(Regex("[A-Z]{2}-[0-9]{4}")))
        }
    }

    @Test
    fun `names do not read as numbers`() {
        // I and O in a catalogue number are read as 1 and 0. Cheap to exclude,
        // impossible to fix once someone has memorised the wrong one.
        (0..300).forEach { seed ->
            val name = UniverseMath.designationFor(seed)
            assertTrue("'$name' contains an ambiguous letter", !name.take(2).contains('I'))
            assertTrue("'$name' contains an ambiguous letter", !name.take(2).contains('O'))
        }
    }

    @Test
    fun `neighbouring places have different names`() {
        // The names are the only thing distinguishing one level from the next, so
        // a collision between adjacent shells would read as not having moved.
        val names = (0..40).map { UniverseMath.designationFor(it) }
        names.zipWithNext().forEachIndexed { i, (a, b) ->
            assertNotEquals("shells $i and ${i + 1} share a designation", a, b)
        }
        assertTrue("designations collide far too often", names.toSet().size >= names.size - 1)
    }

    @Test
    fun `the description says what the place actually is`() {
        (0..60).forEach { seed ->
            val spec = UniverseMath.shellAt(seed)
            val text = UniverseMath.describe(spec)
            assertTrue("shell $seed has no description", text.isNotBlank())
            assertTrue(
                "shell $seed describes ${spec.satellites.size} bodies as: $text",
                text.contains("${spec.satellites.size} "),
            )
            val shape = when (spec.kind) {
                ShellKind.Spiral -> "SPIRAL"
                ShellKind.Ringed -> "RINGED"
                ShellKind.Cluster -> "SWARM"
                ShellKind.Binary -> "BINARY"
            }
            assertTrue("a ${spec.kind} shell is described as '$text'", text.contains(shape))
        }
    }

    @Test
    fun `one body is a body, not one bodies`() {
        // The description is on screen the whole time you are in a place, so the
        // one case where the plural is wrong would be seen constantly.
        val single = UniverseMath.describe(
            UniverseMath.shellAt(0).copy(satellites = UniverseMath.shellAt(0).satellites.take(1)),
        )
        assertTrue("singular reads wrong: $single", single.contains("1 BODY"))
    }

    @Test
    fun `a spiral names how many arms it has`() {
        (0..60).map { UniverseMath.shellAt(it) }
            .filter { it.kind == ShellKind.Spiral }
            .forEach {
                assertTrue("a spiral with ${it.arms} arms is not a spiral", it.arms >= 2)
                assertTrue("shell ${it.seed}: ${UniverseMath.describe(it)}", UniverseMath.describe(it).contains("${it.arms}-ARM"))
            }
    }

    // ---- gestures --------------------------------------------------------

    @Test
    fun `pinching back out closes only after the top level is gone`() {
        assertTrue("a fresh dive would close immediately", !UniverseMath.shouldClose(UniverseMath.START_ZOOM))
        assertTrue("a small wobble at the top closes the view", !UniverseMath.shouldClose(-0.1f))
        assertTrue("pinching out never closes", UniverseMath.shouldClose(-1f))
    }

    @Test
    fun `panning is bounded in both directions`() {
        assertEquals(40f, UniverseMath.clampPan(40f, 100f), 1e-4f)
        assertEquals(100f, UniverseMath.clampPan(400f, 100f), 1e-4f)
        assertEquals(-100f, UniverseMath.clampPan(-400f, 100f), 1e-4f)
    }

    // ---- helpers ---------------------------------------------------------

    private data class DrawnShell(val seed: Int, val scale: Float, val alpha: Float)

    /** Exactly what the renderer would put on screen at [zoom]. */
    private fun frameAt(zoom: Float): List<DrawnShell> {
        val depth = UniverseMath.depthOf(zoom)
        val fraction = UniverseMath.fractionOf(zoom)
        return UniverseMath.SHELLS.map { j ->
            val level = UniverseMath.levelOf(j, fraction)
            DrawnShell(
                seed = UniverseMath.seedFor(depth, j),
                scale = UniverseMath.shellScale(level),
                alpha = UniverseMath.shellAlpha(level),
            )
        }
    }

    private fun assertClose(message: String, expected: Float, actual: Float, tolerance: Float) {
        assertTrue("$message (expected $expected, was $actual)", abs(expected - actual) <= tolerance)
    }
}
