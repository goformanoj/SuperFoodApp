package com.jarvis.os.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.jarvis.os.ui.theme.OrbStyle

/**
 * One frame of orb animation.
 */
data class OrbFrame(
    val radius: Float,
    val accent: Color,
    val secondary: Color,
    val highlight: Color,
    /** Degrees, fast clockwise. */
    val spin: Float,
    /** Degrees, slow clockwise. */
    val drift: Float,
    /** Degrees, counter-clockwise. */
    val counter: Float,
    /** 0..1, eased, for breathing glow. */
    val breathe: Float,
    /** 0..1, live microphone level. */
    val amp: Float,
)

/**
 * One concentric band of the artwork, and how fast it turns.
 *
 * [inner] and [outer] are fractions of the orb radius; [speed] multiplies the
 * drift clock, and its sign is the direction. The app clips the artwork to each
 * band and rotates it, so what moves is the design's OWN rings — not decoration
 * added around the outside, which is what the first attempt did and what the
 * user rejected.
 */
data class OrbBand(val inner: Float, val outer: Float, val speed: Float)

/**
 * How each design's rings turn.
 *
 * Bands are cut where each artwork has a natural gap between rings, so the seam
 * between two differently-turning bands falls somewhere the eye already reads as
 * a boundary. Speeds are deliberately small and mostly alternate in direction:
 * that is what makes concentric rings look like independent machinery rather
 * than one picture spinning.
 */
fun bandsFor(style: OrbStyle): List<OrbBand> = when (style) {
    // Swirling energy — the most motion, four bands churning against each other.
    OrbStyle.Reactor -> listOf(
        OrbBand(0.00f, 0.30f, 0.35f),
        OrbBand(0.30f, 0.55f, -0.75f),
        OrbBand(0.55f, 0.80f, 0.55f),
        OrbBand(0.80f, 1.00f, -0.30f),
    )
    // Crystal hexagon — rigid. The lattice turns nearly as one piece, or the
    // prisms tear apart at the band edges.
    OrbStyle.Lattice -> listOf(
        OrbBand(0.00f, 0.62f, 0.22f),
        OrbBand(0.62f, 1.00f, -0.34f),
    )
    // Gem in a shell: the gem creeps, the dome turns the other way.
    OrbStyle.Prism -> listOf(
        OrbBand(0.00f, 0.34f, 0.40f),
        OrbBand(0.34f, 0.66f, 0.20f),
        OrbBand(0.66f, 1.00f, -0.42f),
    )
    // Filigree — many fine rings, the busiest and the best suited to this.
    OrbStyle.Filigree -> listOf(
        OrbBand(0.00f, 0.26f, 0.30f),
        OrbBand(0.26f, 0.48f, -0.62f),
        OrbBand(0.48f, 0.72f, 0.50f),
        OrbBand(0.72f, 1.00f, -0.36f),
    )
    // Machine: the gear turns, the dome holds almost still.
    OrbStyle.Machine -> listOf(
        OrbBand(0.00f, 0.30f, 0.55f),
        OrbBand(0.30f, 0.62f, -0.70f),
        OrbBand(0.62f, 1.00f, 0.18f),
    )
    // Nebula: spiral arms sweep, the outer wireframe drifts back.
    OrbStyle.Nebula -> listOf(
        OrbBand(0.00f, 0.32f, 0.28f),
        OrbBand(0.32f, 0.70f, 0.60f),
        OrbBand(0.70f, 1.00f, -0.32f),
    )
}

/**
 * The only thing still drawn around the artwork: a bloom that answers the
 * microphone.
 *
 * Everything else that used to live here — dotted rings, orbiting dust, flares
 * — was deleted. It sat OUTSIDE the art and read as decoration bolted onto a
 * picture rather than as the design being alive. The artwork's own rings now
 * carry the motion.
 */
fun DrawScope.drawOrbBloom(f: OrbFrame) {
    val r = f.radius * (1.02f + f.amp * 0.18f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                f.accent.copy(alpha = 0.04f + f.amp * 0.20f + f.breathe * 0.03f),
                Color.Transparent,
            ),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}
