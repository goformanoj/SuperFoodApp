package com.jarvis.os.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import com.jarvis.os.ui.theme.OrbStyle
import kotlin.math.cos
import kotlin.math.sin

/**
 * One frame of orb animation: the geometry is fixed, these are the values that
 * move.
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
 * How each theme's overlay behaves. The artwork now carries the orb's identity,
 * so these differ in motion and emphasis rather than in shape.
 */
private data class OverlaySpec(
    val rings: List<Float>,
    val ringGap: Float,
    val particles: Int,
    val particleInner: Float,
    val flares: Int,
    /** Warm themes look right with gold sparks; the blue ones with cyan. */
    val useHighlight: Boolean,
)

private fun specFor(style: OrbStyle): OverlaySpec = when (style) {
    // Swirling energy: several close rings, lots of dust.
    OrbStyle.Reactor -> OverlaySpec(listOf(1.02f, 1.09f, 1.17f), 3.5f, 34, 0.55f, 2, true)
    // Crystalline: sparse, precise, few particles.
    OrbStyle.Lattice -> OverlaySpec(listOf(1.03f, 1.13f), 5f, 18, 0.70f, 3, true)
    // Gem in a shell: a wide outer ring and a gold sparkle.
    OrbStyle.Prism -> OverlaySpec(listOf(1.04f, 1.12f, 1.21f), 4f, 26, 0.62f, 2, true)
    // Filigree: many fine rings, the busiest overlay.
    OrbStyle.Filigree -> OverlaySpec(listOf(1.01f, 1.06f, 1.11f, 1.17f), 3f, 30, 0.58f, 2, true)
    // Machine: two firm rings, sparse dust.
    OrbStyle.Machine -> OverlaySpec(listOf(1.03f, 1.14f), 4.5f, 20, 0.66f, 1, true)
    // Nebula: wide sweeping rings and a star field.
    OrbStyle.Nebula -> OverlaySpec(listOf(1.05f, 1.15f, 1.26f), 4f, 44, 0.50f, 3, false)
}

/**
 * The animated layer drawn OVER the theme artwork.
 *
 * The hand-drawn orbs this replaced were the wrong approach: the reference
 * designs are photorealistic renders, and vector shapes on a Canvas cannot
 * reach them — two attempts proved it. The artwork is now the orb, and this
 * supplies what a static image cannot: rotation, dust, flares and a core that
 * answers the microphone.
 *
 * Everything here stays OUTSIDE the artwork's bright centre. Drawing over the
 * render would only muddy work that is already better than anything this can
 * add.
 */
fun DrawScope.drawOrbOverlay(style: OrbStyle, f: OrbFrame) {
    val spec = specFor(style)
    val spark = if (spec.useHighlight) f.highlight else f.accent

    // Reactive bloom behind the art.
    val bloomR = f.radius * (1.05f + f.amp * 0.20f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                f.accent.copy(alpha = 0.05f + f.amp * 0.22f),
                Color.Transparent,
            ),
            center = center,
            radius = bloomR,
        ),
        radius = bloomR,
        center = center,
    )

    // Counter-rotating dotted rings just outside the art.
    spec.rings.forEachIndexed { i, frac ->
        val rot = if (i % 2 == 0) f.drift * (1f + i * 0.35f) else -f.counter * (0.8f + i * 0.25f)
        rotate(rot, center) {
            dottedRing(
                radius = f.radius * frac,
                color = (if (i % 2 == 0) f.accent else spark).copy(alpha = 0.55f - i * 0.09f),
                width = if (i == 0) 1.5f else 1.1f,
                dotLength = 1.4f,
                gap = spec.ringGap,
            )
        }
    }

    // A bright arc travelling round the outermost ring — the clearest signal
    // that the orb is live rather than a picture.
    val trackR = f.radius * (spec.rings.last() + 0.04f)
    rotate(f.spin, center) {
        dottedArc(trackR, 0f, 52f, f.accent.copy(alpha = 0.9f), width = 2.4f, dotLength = 3f, gap = 2.5f)
    }
    rotate(-f.spin * 1.6f + 180f, center) {
        dottedArc(trackR, 0f, 26f, spark.copy(alpha = 0.8f), width = 2f, dotLength = 2.5f, gap = 2.5f)
    }

    // Orbiting dust, deterministic so it drifts rather than flickers.
    rotate(f.drift * 0.6f, center) {
        for (i in 0 until spec.particles) {
            val a = OrbMath.range(i * 7 + 31, 0f, OrbMath.TAU)
            val rad = f.radius * OrbMath.range(i * 7 + 977, spec.particleInner, 1.30f)
            drawCircle(
                color = (if (i % 4 == 0) spark else f.accent)
                    .copy(alpha = OrbMath.range(i * 7 + 55, 0.16f, 0.70f)),
                radius = px(OrbMath.range(i * 7 + 313, 0.7f, 2.0f)),
                center = Offset(center.x + cos(a) * rad, center.y + sin(a) * rad),
            )
        }
    }

    // Flares riding the rings, brightening as the room gets louder.
    rotate(f.spin * 0.8f, center) {
        for (i in 0 until spec.flares) {
            val a = OrbMath.spokeAngle(i, spec.flares)
            val rad = f.radius * spec.rings[i % spec.rings.size]
            flare(
                at = Offset(center.x + cos(a) * rad, center.y + sin(a) * rad),
                size = f.radius * (0.045f + f.amp * 0.03f),
                color = if (i % 2 == 0) spark else f.accent,
                alpha = 0.55f + f.breathe * 0.25f + f.amp * 0.20f,
            )
        }
    }
}
