package com.jarvis.os.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.jarvis.os.ui.theme.OrbStyle
import kotlin.math.cos
import kotlin.math.sin

/**
 * One frame of orb animation: the geometry is fixed, these are the values that
 * move. Passed as one object so each renderer has a signature that does not
 * grow every time a new motion is added.
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
    /** 0..1, eased, for breathing scale and glow. */
    val breathe: Float,
    /** 0..1, live microphone level. */
    val amp: Float,
)

/** Draws whichever centrepiece [style] calls for. */
fun DrawScope.drawOrbStyle(style: OrbStyle, f: OrbFrame) {
    when (style) {
        OrbStyle.Reactor -> drawReactor(f)
        OrbStyle.Lattice -> drawLattice(f)
        OrbStyle.Prism -> drawPrism(f)
        OrbStyle.Filigree -> drawFiligree(f)
        OrbStyle.Machine -> drawMachine(f)
        OrbStyle.Nebula -> drawNebula(f)
    }
}

// --- shared pieces -------------------------------------------------------

/** The soft radial bloom every style sits inside. */
private fun DrawScope.drawBloom(f: OrbFrame, color: Color, scale: Float = 1f) {
    val r = f.radius * scale * (0.9f + f.amp * 0.16f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.22f + f.amp * 0.30f), Color.Transparent),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

/** Dust motes on a ring, deterministic so they do not jitter between frames. */
private fun DrawScope.drawMotes(
    f: OrbFrame,
    count: Int,
    innerFraction: Float,
    outerFraction: Float,
    color: Color,
    seedBase: Int,
    rotation: Float,
) {
    rotate(rotation, center) {
        for (i in 0 until count) {
            val a = OrbMath.range(seedBase + i, 0f, OrbMath.TAU)
            val rad = f.radius * OrbMath.range(seedBase + i + 977, innerFraction, outerFraction)
            val dotR = dpOf(OrbMath.range(seedBase + i + 313, 0.8f, 2.4f))
            val alpha = OrbMath.range(seedBase + i + 55, 0.20f, 0.75f)
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = dotR,
                center = Offset(center.x + OrbMath.xAt(a, rad), center.y + OrbMath.yAt(a, rad)),
            )
        }
    }
}

/** A geodesic dome: nodes on a ring joined to their neighbours. */
private fun DrawScope.drawDome(
    f: OrbFrame,
    nodes: Int,
    fraction: Float,
    color: Color,
    rotation: Float,
) {
    val rad = f.radius * fraction
    rotate(rotation, center) {
        for (i in 0 until nodes) {
            val a1 = OrbMath.spokeAngle(i, nodes)
            val a2 = OrbMath.spokeAngle(i + 1, nodes)
            val p1 = Offset(center.x + OrbMath.xAt(a1, rad), center.y + OrbMath.yAt(a1, rad))
            val p2 = Offset(center.x + OrbMath.xAt(a2, rad), center.y + OrbMath.yAt(a2, rad))
            drawLine(color.copy(alpha = 0.35f), p1, p2, dpOf(1f))
            // Strut inward, giving the dome its depth without a 3D projection.
            val inner = Offset(
                center.x + OrbMath.xAt(a1, rad * 0.72f),
                center.y + OrbMath.yAt(a1, rad * 0.72f),
            )
            drawLine(color.copy(alpha = 0.18f), p1, inner, dpOf(1f))
            drawCircle(color.copy(alpha = 0.75f), dpOf(2.2f), p1)
        }
    }
}

private fun DrawScope.arcRing(
    fraction: Float,
    start: Float,
    sweep: Float,
    color: Color,
    width: Float,
    radius: Float,
) {
    val rad = radius * fraction
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - rad, center.y - rad),
        size = Size(rad * 2f, rad * 2f),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}

/**
 * Stroke widths in dp without importing the unit type. Canvas works in raw
 * pixels, so a hardcoded width would be hairline on a dense screen.
 */
private fun DrawScope.dpOf(value: Float): Float = value * density

// --- 1. Arc Reactor ------------------------------------------------------

/** Swirling cyan and gold energy bands, counter-rotating. */
private fun DrawScope.drawReactor(f: OrbFrame) {
    drawBloom(f, f.accent)

    // Broad energy bands, alternating direction so the whole thing churns.
    val bands = listOf(
        Triple(0.94f, 0f, 1f), Triple(0.80f, 140f, -1f),
        Triple(0.66f, 40f, 1f), Triple(0.52f, 220f, -1f),
    )
    bands.forEachIndexed { i, (frac, offset, dir) ->
        val rot = if (dir > 0) f.spin else f.counter
        rotate(rot * (0.6f + i * 0.18f) + offset, center) {
            arcRing(frac, 0f, 150f, f.accent.copy(alpha = 0.55f), dpOf(4f + f.amp * 3f), f.radius)
            arcRing(frac, 190f, 90f, f.accent.copy(alpha = 0.30f), dpOf(3f), f.radius)
        }
    }

    // Gold counter-arcs — the warm signature in the design.
    rotate(f.drift, center) {
        arcRing(0.88f, 20f, 110f, f.highlight.copy(alpha = 0.85f), dpOf(3.5f), f.radius)
        arcRing(0.72f, 200f, 80f, f.highlight.copy(alpha = 0.6f), dpOf(3f), f.radius)
    }
    rotate(-f.drift * 1.4f, center) {
        arcRing(0.60f, 120f, 70f, f.highlight.copy(alpha = 0.45f), dpOf(2.5f), f.radius)
    }

    drawMotes(f, 46, 0.35f, 1.0f, f.accent, seedBase = 11, rotation = f.drift * 0.3f)

    // Reactive core.
    val coreR = f.radius * 0.30f * (0.92f + f.breathe * 0.10f + f.amp * 0.22f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.85f), f.accent.copy(alpha = 0.55f), Color.Transparent),
            center = center,
            radius = coreR * 1.6f,
        ),
        radius = coreR,
        center = center,
    )
}

// --- 2. Lattice ----------------------------------------------------------

/** Six crystal prisms in a hexagon, joined by gold circuit traces. */
private fun DrawScope.drawLattice(f: OrbFrame) {
    drawBloom(f, f.accent, 0.95f)

    drawDome(f, nodes = 18, fraction = 1.0f, color = f.accent, rotation = f.drift * 0.4f)

    val ringR = f.radius * 0.62f
    val vertices = 6
    rotate(f.drift, center) {
        // Gold traces along the hexagon edges, drawn first so crystals sit on top.
        for (i in 0 until vertices) {
            val a1 = OrbMath.spokeAngle(i, vertices, -90f)
            val a2 = OrbMath.spokeAngle(i + 1, vertices, -90f)
            val p1 = Offset(center.x + OrbMath.xAt(a1, ringR), center.y + OrbMath.yAt(a1, ringR))
            val p2 = Offset(center.x + OrbMath.xAt(a2, ringR), center.y + OrbMath.yAt(a2, ringR))
            drawLine(f.highlight.copy(alpha = 0.9f), p1, p2, dpOf(3f), cap = StrokeCap.Round)
            drawLine(f.highlight.copy(alpha = 0.35f), p1, p2, dpOf(7f), cap = StrokeCap.Round)
        }

        // A crystal at each vertex: an upper and a lower facet, lit differently.
        for (i in 0 until vertices) {
            val a = OrbMath.spokeAngle(i, vertices, -90f)
            val cx = center.x + OrbMath.xAt(a, ringR)
            val cy = center.y + OrbMath.yAt(a, ringR)
            val s = f.radius * (0.20f + f.amp * 0.03f)

            val top = Path().apply {
                moveTo(cx, cy - s)
                lineTo(cx - s * 0.72f, cy)
                lineTo(cx + s * 0.72f, cy)
                close()
            }
            val bottom = Path().apply {
                moveTo(cx, cy + s * 0.9f)
                lineTo(cx - s * 0.72f, cy)
                lineTo(cx + s * 0.72f, cy)
                close()
            }
            drawPath(top, f.accent.copy(alpha = 0.75f))
            drawPath(bottom, f.secondary.copy(alpha = 0.65f))
            drawPath(top, f.accent, style = Stroke(dpOf(1.2f)))
            drawPath(bottom, f.accent.copy(alpha = 0.7f), style = Stroke(dpOf(1.2f)))
        }
    }

    // Dark hex well at the centre, as in the design.
    rotate(-f.counter * 0.5f, center) {
        val innerR = f.radius * 0.30f
        val hex = Path()
        for (i in 0 until 6) {
            val a = OrbMath.spokeAngle(i, 6, -90f)
            val x = center.x + OrbMath.xAt(a, innerR)
            val y = center.y + OrbMath.yAt(a, innerR)
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
        drawPath(hex, f.accent.copy(alpha = 0.18f + f.amp * 0.25f))
        drawPath(hex, f.accent.copy(alpha = 0.8f), style = Stroke(dpOf(1.5f)))
    }
}

// --- 3. Prism ------------------------------------------------------------

/** A low-poly faceted gem, wrapped in a node dome, with a gold vortex inside. */
private fun DrawScope.drawPrism(f: OrbFrame) {
    drawBloom(f, f.accent, 0.95f)
    drawDome(f, nodes = 22, fraction = 1.0f, color = f.highlight, rotation = f.drift * 0.35f)

    // Two latitude bands of alternating facets — reads as a faceted sphere
    // without needing a real projection.
    val bands = listOf(0.66f to 14, 0.44f to 10)
    rotate(f.drift * 0.8f, center) {
        bands.forEachIndexed { bandIndex, (frac, facets) ->
            val outer = f.radius * frac
            val inner = f.radius * frac * 0.62f
            for (i in 0 until facets) {
                val a1 = OrbMath.spokeAngle(i, facets)
                val a2 = OrbMath.spokeAngle(i + 1, facets)
                val mid = (a1 + a2) / 2f
                val up = (i + bandIndex) % 2 == 0
                val path = Path().apply {
                    if (up) {
                        moveTo(center.x + OrbMath.xAt(mid, outer), center.y + OrbMath.yAt(mid, outer))
                        lineTo(center.x + OrbMath.xAt(a1, inner), center.y + OrbMath.yAt(a1, inner))
                        lineTo(center.x + OrbMath.xAt(a2, inner), center.y + OrbMath.yAt(a2, inner))
                    } else {
                        moveTo(center.x + OrbMath.xAt(mid, inner), center.y + OrbMath.yAt(mid, inner))
                        lineTo(center.x + OrbMath.xAt(a1, outer), center.y + OrbMath.yAt(a1, outer))
                        lineTo(center.x + OrbMath.xAt(a2, outer), center.y + OrbMath.yAt(a2, outer))
                    }
                    close()
                }
                val fill = if (up) f.accent else f.highlight
                drawPath(path, fill.copy(alpha = if (up) 0.55f else 0.42f))
                drawPath(path, fill.copy(alpha = 0.9f), style = Stroke(dpOf(1f)))
            }
        }
    }

    // Gold vortex: motes spiralling into the middle.
    val arms = 3
    val perArm = 16
    rotate(f.spin, center) {
        for (arm in 0 until arms) {
            for (i in 0 until perArm) {
                val t = i.toFloat() / perArm
                val rad = OrbMath.spiralRadius(i, perArm, f.radius * 0.06f, f.radius * 0.34f)
                val a = OrbMath.spokeAngle(arm, arms) + t * 2.4f
                drawCircle(
                    color = f.highlight.copy(alpha = 0.85f - t * 0.55f),
                    radius = dpOf(1.6f),
                    center = Offset(center.x + OrbMath.xAt(a, rad), center.y + OrbMath.yAt(a, rad)),
                )
            }
        }
    }

    val coreR = f.radius * 0.16f * (0.9f + f.amp * 0.35f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(f.highlight.copy(alpha = 0.9f), Color.Transparent),
            center = center,
            radius = coreR * 2f,
        ),
        radius = coreR,
        center = center,
    )
}

// --- 4. Filigree ---------------------------------------------------------

/** Ornate concentric copper rings around a molten core. */
private fun DrawScope.drawFiligree(f: OrbFrame) {
    drawBloom(f, f.accent, 1.0f)

    // Fine concentric rings, each with its own spoke count and drift.
    val rings = listOf(0.98f, 0.90f, 0.80f, 0.68f, 0.56f, 0.44f)
    rings.forEachIndexed { i, frac ->
        val rot = if (i % 2 == 0) f.drift * (1f + i * 0.2f) else -f.drift * (0.8f + i * 0.15f)
        rotate(rot, center) {
            drawCircle(
                color = f.accent.copy(alpha = 0.30f + 0.06f * i),
                radius = f.radius * frac,
                center = center,
                style = Stroke(dpOf(1.2f)),
            )
            // Scalloped petals: short arcs evenly spaced, the filigree texture.
            val petals = 12 + i * 4
            val step = 360f / petals
            for (p in 0 until petals) {
                arcRing(
                    frac,
                    p * step,
                    step * 0.55f,
                    f.accent.copy(alpha = 0.55f),
                    dpOf(2.2f),
                    f.radius,
                )
            }
        }
    }

    // Radial spokes through the outer band.
    rotate(f.counter * 0.4f, center) {
        val spokes = 36
        for (i in 0 until spokes) {
            val a = OrbMath.spokeAngle(i, spokes)
            val rIn = f.radius * 0.82f
            val rOut = f.radius * 0.96f
            drawLine(
                color = f.highlight.copy(alpha = if (i % 3 == 0) 0.7f else 0.25f),
                start = Offset(center.x + OrbMath.xAt(a, rIn), center.y + OrbMath.yAt(a, rIn)),
                end = Offset(center.x + OrbMath.xAt(a, rOut), center.y + OrbMath.yAt(a, rOut)),
                strokeWidth = dpOf(1.4f),
            )
        }
    }

    drawMotes(f, 30, 0.5f, 0.95f, f.highlight, seedBase = 501, rotation = f.spin * 0.2f)

    // Molten glass core — the deep red centre in the design.
    val coreR = f.radius * 0.26f * (0.94f + f.breathe * 0.08f + f.amp * 0.2f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                f.highlight.copy(alpha = 0.9f),
                f.secondary.copy(alpha = 0.85f),
                f.secondary.copy(alpha = 0.2f),
                Color.Transparent,
            ),
            center = Offset(center.x - coreR * 0.25f, center.y - coreR * 0.3f),
            radius = coreR * 1.9f,
        ),
        radius = coreR,
        center = center,
    )
    drawCircle(f.accent.copy(alpha = 0.8f), coreR, center, style = Stroke(dpOf(2f)))
}

// --- 5. Machine ----------------------------------------------------------

/** A turning gear core ringed by violet crystal shards. */
private fun DrawScope.drawMachine(f: OrbFrame) {
    drawBloom(f, f.accent, 0.95f)
    drawDome(f, nodes = 20, fraction = 1.0f, color = f.secondary, rotation = f.drift * 0.3f)

    // The gear: teeth as short thick radial bars, turning steadily.
    val teeth = 22
    val gearR = f.radius * 0.50f
    rotate(f.spin * 0.5f, center) {
        for (i in 0 until teeth) {
            val a = OrbMath.spokeAngle(i, teeth)
            drawLine(
                color = f.secondary.copy(alpha = 0.9f),
                start = Offset(center.x + OrbMath.xAt(a, gearR), center.y + OrbMath.yAt(a, gearR)),
                end = Offset(
                    center.x + OrbMath.xAt(a, gearR * 1.16f),
                    center.y + OrbMath.yAt(a, gearR * 1.16f),
                ),
                strokeWidth = dpOf(6f),
                cap = StrokeCap.Butt,
            )
        }
        drawCircle(f.secondary.copy(alpha = 0.85f), gearR, center, style = Stroke(dpOf(4f)))
    }

    // Inner mechanical rings, counter-turning.
    rotate(-f.spin * 0.9f, center) {
        drawCircle(f.highlight.copy(alpha = 0.55f), f.radius * 0.36f, center, style = Stroke(dpOf(2.5f)))
        val spokes = 8
        for (i in 0 until spokes) {
            val a = OrbMath.spokeAngle(i, spokes)
            drawLine(
                color = f.highlight.copy(alpha = 0.6f),
                start = Offset(center.x + OrbMath.xAt(a, f.radius * 0.14f), center.y + OrbMath.yAt(a, f.radius * 0.14f)),
                end = Offset(center.x + OrbMath.xAt(a, f.radius * 0.36f), center.y + OrbMath.yAt(a, f.radius * 0.36f)),
                strokeWidth = dpOf(2.5f),
            )
        }
    }

    // Crystal shards ringing the machine.
    val shards = 10
    rotate(f.drift * 1.2f, center) {
        for (i in 0 until shards) {
            val a = OrbMath.spokeAngle(i, shards)
            val baseR = f.radius * 0.70f
            val tipR = baseR + f.radius * (0.16f + f.amp * 0.04f)
            val spread = 0.16f
            val path = Path().apply {
                moveTo(center.x + OrbMath.xAt(a, tipR), center.y + OrbMath.yAt(a, tipR))
                lineTo(center.x + OrbMath.xAt(a - spread, baseR), center.y + OrbMath.yAt(a - spread, baseR))
                lineTo(center.x + OrbMath.xAt(a + spread, baseR), center.y + OrbMath.yAt(a + spread, baseR))
                close()
            }
            drawPath(path, if (i % 2 == 0) f.accent.copy(alpha = 0.7f) else f.highlight.copy(alpha = 0.5f))
            drawPath(path, f.accent.copy(alpha = 0.9f), style = Stroke(dpOf(1f)))
        }
    }

    val coreR = f.radius * 0.11f * (0.9f + f.amp * 0.4f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.8f), f.highlight.copy(alpha = 0.5f), Color.Transparent),
            center = center,
            radius = coreR * 2.4f,
        ),
        radius = coreR,
        center = center,
    )
}

// --- 6. Nebula -----------------------------------------------------------

/** Spiral particle arms round a crystal flower, over a starfield. */
private fun DrawScope.drawNebula(f: OrbFrame) {
    drawBloom(f, f.accent, 1.05f)

    // Static starfield — deterministic, so the stars hold still while the arms turn.
    drawMotes(f, 54, 0.30f, 1.15f, Color.White, seedBase = 4001, rotation = 0f)

    // Spiral arms of dust.
    val arms = 4
    val perArm = 26
    for (arm in 0 until arms) {
        rotate(f.spin * (0.5f + arm * 0.06f), center) {
            for (i in 0 until perArm) {
                val t = i.toFloat() / perArm
                val rad = OrbMath.spiralRadius(i, perArm, f.radius * 0.24f, f.radius * 1.02f)
                val a = OrbMath.spokeAngle(arm, arms) + t * 1.9f
                val color = if (arm % 2 == 0) f.accent else f.secondary
                drawCircle(
                    color = color.copy(alpha = 0.75f - t * 0.4f),
                    radius = dpOf(1.4f + (1f - t) * 1.6f),
                    center = Offset(center.x + OrbMath.xAt(a, rad), center.y + OrbMath.yAt(a, rad)),
                )
            }
        }
    }

    drawDome(f, nodes = 14, fraction = 0.98f, color = f.secondary, rotation = -f.drift * 0.5f)

    // Crystal flower: pointed petals round a dark hub.
    val petals = 12
    val petalOuter = f.radius * (0.34f + f.amp * 0.04f)
    rotate(f.counter * 0.6f, center) {
        for (i in 0 until petals) {
            val a = OrbMath.spokeAngle(i, petals)
            val spread = 0.22f
            val path = Path().apply {
                moveTo(center.x + OrbMath.xAt(a, petalOuter), center.y + OrbMath.yAt(a, petalOuter))
                lineTo(
                    center.x + OrbMath.xAt(a - spread, f.radius * 0.17f),
                    center.y + OrbMath.yAt(a - spread, f.radius * 0.17f),
                )
                lineTo(
                    center.x + OrbMath.xAt(a + spread, f.radius * 0.17f),
                    center.y + OrbMath.yAt(a + spread, f.radius * 0.17f),
                )
                close()
            }
            drawPath(path, f.accent.copy(alpha = if (i % 2 == 0) 0.8f else 0.55f))
            drawPath(path, f.highlight.copy(alpha = 0.7f), style = Stroke(dpOf(1f)))
        }
    }

    // The hub reads as a dark well the petals sit around, as in the design.
    val hubR = f.radius * 0.16f
    drawCircle(Color.Black.copy(alpha = 0.55f), hubR, center)
    drawCircle(f.highlight.copy(alpha = 0.85f), hubR, center, style = Stroke(dpOf(2f)))
    drawCircle(
        brush = Brush.radialGradient(
            listOf(f.highlight.copy(alpha = 0.55f + f.amp * 0.35f), Color.Transparent),
            center = center,
            radius = hubR * 1.6f,
        ),
        radius = hubR * 0.9f,
        center = center,
    )
}
