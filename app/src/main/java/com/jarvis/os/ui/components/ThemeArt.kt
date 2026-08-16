package com.jarvis.os.ui.components

import androidx.compose.ui.graphics.Color
import com.jarvis.os.ui.theme.OrbStyle

/**
 * Colour sampled straight out of the reference renders.
 *
 * Hand-picking accent colours got the themes into the right family and no
 * closer. These ramps are measured instead: each reference was resampled about
 * its centre, and for every tenth of the radius the brightest quartile of pixels
 * in that annulus was averaged — the brightest quartile because the mean of a
 * whole annulus is dominated by the dark gaps between rings, and it is the rings
 * that need matching. The baked-in wordmark was masked out of the sample.
 *
 * So a ring at 0.6 of the orb's radius is drawn in the colour the reference
 * actually has at 0.6 of its radius. That is as close to the source as a
 * procedural orb can get: the geometry is ours, the light is theirs.
 */
object ThemeArt {

    /** Ten stops, centre outward. */
    fun ramp(style: OrbStyle): List<Color> = when (style) {
        OrbStyle.Reactor -> ARC
        OrbStyle.Lattice -> LATTICE
        OrbStyle.Prism -> PRISM
        OrbStyle.Filigree -> FORGE
        OrbStyle.Machine -> CORE
        OrbStyle.Nebula -> NEBULA
        OrbStyle.Orbit -> ORBIT
    }

    /** The colour the reference has at [fraction] of its radius, interpolated. */
    fun at(style: OrbStyle, fraction: Float): Color {
        val stops = ramp(style)
        val f = fraction.coerceIn(0f, 1f) * (stops.size - 1)
        val i = f.toInt().coerceAtMost(stops.size - 2)
        return lerp(stops[i], stops[i + 1], f - i)
    }

    /** The colour behind the orb, measured from the reference's corners. */
    fun backdrop(style: OrbStyle): Color = when (style) {
        OrbStyle.Reactor -> Color(0xFF1A3543)
        OrbStyle.Lattice -> Color(0xFF174155)
        OrbStyle.Prism -> Color(0xFF431E2F)
        OrbStyle.Filigree -> Color(0xFF341A1E)
        OrbStyle.Machine -> Color(0xFF421F36)
        OrbStyle.Nebula -> Color(0xFF4B2E42)
        // Near-black: the reference is deep space with one lit object in it, so
        // there is no corner colour to measure the way the other six have.
        OrbStyle.Orbit -> Color(0xFF071426)
    }

    private fun lerp(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            a.red + (b.red - a.red) * k,
            a.green + (b.green - a.green) * k,
            a.blue + (b.blue - a.blue) * k,
            1f,
        )
    }

    private val ARC = listOf(
        Color(0xFF0B253A), Color(0xFF1E4057), Color(0xFF265E78), Color(0xFF55A9B3),
        Color(0xFF66B6B2), Color(0xFF7ACFC2), Color(0xFF5FB5B0), Color(0xFF94A48F),
        Color(0xFF78B4AC), Color(0xFF45B1B9),
    )
    private val LATTICE = listOf(
        Color(0xFF183951), Color(0xFF609AA4), Color(0xFF87BEC0), Color(0xFF86D7DE),
        Color(0xFF77C6D1), Color(0xFF98C3BF), Color(0xFF95CED2), Color(0xFF79C2C8),
        Color(0xFF6CC0CB), Color(0xFF56A0B2),
    )
    private val PRISM = listOf(
        Color(0xFF905073), Color(0xFFBA788D), Color(0xFFD89DC9), Color(0xFFD9A1B1),
        Color(0xFFDDA2B9), Color(0xFFC6829F), Color(0xFF894F6C), Color(0xFF8C5168),
        Color(0xFF9C5C70), Color(0xFF813C5B),
    )
    private val FORGE = listOf(
        Color(0xFFC56C3E), Color(0xFFE7A56E), Color(0xFFE1A672), Color(0xFFD79A6A),
        Color(0xFFDA9C6C), Color(0xFFC78559), Color(0xFFAD6D4B), Color(0xFFB16F4D),
        Color(0xFF9C583E), Color(0xFF8E4F39),
    )
    private val CORE = listOf(
        Color(0xFFB86C74), Color(0xFFB2687F), Color(0xFFCC86C8), Color(0xFFD190A6),
        Color(0xFFD493BB), Color(0xFFBD749C), Color(0xFF8D5674), Color(0xFF96596E),
        Color(0xFF925472), Color(0xFF8C4367),
    )

    /**
     * Orbit — sampled centre-outward from the 2026-08-16 mockup: a white-hot
     * core, cyan through the body, then violet out at the widest orbital rings.
     * The cyan-to-violet swing across the radius is what distinguishes this from
     * the other blue themes, which run cyan to blue and stay there.
     *
     * The outermost stops are deliberately BRIGHT, like every other ramp here.
     * A first draft ended on near-black because that is what the reference's
     * corners look like — but this table colours the orb's own geometry at each
     * radius, not the space behind it (that is [backdrop]). A dark final stop
     * made the widest, most characteristic rings almost invisible, and the
     * ramp-brightness test caught it before it shipped.
     */
    private val ORBIT = listOf(
        Color(0xFFF2FBFF),
        Color(0xFFBDEEFF),
        Color(0xFF6FDCFF),
        Color(0xFF34C8FF),
        Color(0xFF1FA6F5),
        Color(0xFF2B7BE4),
        Color(0xFF4A5BDA),
        Color(0xFF6B4CD0),
        Color(0xFF8A6BE8),
        Color(0xFFA98CF5),
    )

    private val NEBULA = listOf(
        Color(0xFFC297B1), Color(0xFFBD88C1), Color(0xFFB87FBD), Color(0xFFAF77B9),
        Color(0xFFBA7FB6), Color(0xFFC18FB6), Color(0xFFA46B84), Color(0xFFB07792),
        Color(0xFF996088), Color(0xFF905677),
    )
}
