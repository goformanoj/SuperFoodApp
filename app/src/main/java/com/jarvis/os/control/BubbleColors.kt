package com.jarvis.os.control

/**
 * The theme colours the floating orb draws itself in, as plain ARGB ints.
 *
 * ## Why this is a copy rather than a reference
 *
 * The real palette is [com.jarvis.os.ui.theme.JarvisPalette], and reaching for it
 * from here would be the obvious thing. It is also the one thing that must not
 * happen: `JarvisPalette` holds Compose `Color`s, and the moment anything under
 * `control/` imports Compose, the off-device gate (`scripts/jvmcheck`) stops
 * compiling — it can build the 50 non-UI sources precisely BECAUSE none of them
 * reaches into `ui/`. Losing that would cost every future change a twenty-minute
 * CI round trip to discover a typo.
 *
 * The bubble is also drawn by a plain `View` on a plain `Canvas`, not by Compose,
 * so it wants ints anyway.
 *
 * So the values are duplicated, deliberately, and the duplication is guarded:
 * `JarvisPaletteBubbleTest` (which lives under `ui/` and therefore runs in CI
 * where Compose exists) asserts every value here still equals its counterpart. A
 * copy nobody checks is a copy that silently drifts.
 */
object BubbleColors {

    /** What the bubble needs to draw itself: a core, a rim, and a warm accent. */
    data class Scheme(
        val accent: Int,
        val secondary: Int,
        val highlight: Int,
        val background: Int,
    )

    /** Theme ids, in `JarvisPalette` order. */
    val IDS = listOf("arc", "forge", "nebula", "orbit")

    private val ARC = Scheme(0xFF22E0F5.toInt(), 0xFF0A84FF.toInt(), 0xFFF5B944.toInt(), 0xFF04101E.toInt())
    private val FORGE = Scheme(0xFFF0A44B.toInt(), 0xFFC2410C.toInt(), 0xFFFFD79A.toInt(), 0xFF120A07.toInt())
    private val NEBULA = Scheme(0xFFC084FC.toInt(), 0xFFE0845C.toInt(), 0xFF7DD3FC.toInt(), 0xFF0F0518.toInt())
    private val ORBIT = Scheme(0xFF29D6FF.toInt(), 0xFF7A5CFF.toInt(), 0xFFB98CFF.toInt(), 0xFF03070F.toInt())

    /**
     * The scheme for a stored theme id. An unknown id falls back to Arc rather
     * than throwing: this is read on a draw path, and a preferences string the
     * user could not have typed is still not worth crashing a window over.
     */
    fun forTheme(id: String): Scheme = when (id) {
        "forge" -> FORGE
        "nebula" -> NEBULA
        "orbit" -> ORBIT
        else -> ARC
    }

    /** All schemes, in [IDS] order — for the test that guards the duplication. */
    fun all(): List<Scheme> = IDS.map { forTheme(it) }
}
