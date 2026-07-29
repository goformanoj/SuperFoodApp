package com.jarvis.os.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

/** The soft radial bloom every style sits inside. */
private fun DrawScope.bloom(f: OrbFrame, color: Color, scale: Float = 1f) {
    val r = f.radius * scale * (0.9f + f.amp * 0.16f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.20f + f.amp * 0.28f), Color.Transparent),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

/** A point on a circle around the orb centre. */
private fun DrawScope.at(angle: Float, radius: Float) =
    Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)

// --- 1. Arc Reactor ------------------------------------------------------

/**
 * Swirling cyan and gold energy ribbons over a dotted particle shell, with
 * flares where the bands cross. The design's rings are dotted light strips, not
 * solid strokes, and the ribbons glow rather than being drawn as flat arcs.
 */
private fun DrawScope.drawReactor(f: OrbFrame) {
    bloom(f, f.accent, 1.05f)

    // The dotted shell behind everything.
    rotate(f.drift * 0.25f, center) {
        wireSphere(f.radius * 0.99f, f.accent, latitudes = 4, meridians = 7, alpha = 0.20f)
    }

    // Fine hatched texture in the outer band.
    rotate(-f.drift * 0.5f, center) {
        radialHatch(f.radius * 0.86f, f.radius * 0.97f, 84, f.accent, alpha = 0.16f)
    }

    // Dotted particle rings — the strips that give the design its texture.
    listOf(0.92f, 0.83f, 0.71f, 0.60f, 0.47f).forEachIndexed { i, frac ->
        val rot = if (i % 2 == 0) f.drift * (1f + i * 0.3f) else -f.drift * (0.8f + i * 0.2f)
        rotate(rot, center) {
            dottedRing(f.radius * frac, f.accent, width = 1.2f, dotLength = 1.4f, gap = 3.5f)
        }
    }

    // Cyan energy ribbons, counter-rotating at four radii.
    val bands = listOf(
        Triple(0.90f, 10f, true), Triple(0.76f, 150f, false),
        Triple(0.63f, 60f, true), Triple(0.50f, 230f, false),
    )
    bands.forEachIndexed { i, (frac, offset, forward) ->
        val rot = (if (forward) f.spin else f.counter) * (0.55f + i * 0.2f) + offset
        rotate(rot, center) {
            energyRibbon(f.radius * frac, 0f, 165f, f.accent, width = 2.2f + f.amp * 1.6f)
            energyRibbon(f.radius * frac, 200f, 70f, f.accent, width = 1.6f, alpha = 0.6f)
        }
    }

    // Gold ribbons weaving the other way — the warm signature of this design.
    rotate(f.drift * 1.3f, center) {
        energyRibbon(f.radius * 0.87f, 15f, 130f, f.highlight, width = 2.4f)
        energyRibbon(f.radius * 0.68f, 195f, 95f, f.highlight, width = 2.0f, alpha = 0.8f)
    }
    rotate(-f.drift * 1.8f, center) {
        energyRibbon(f.radius * 0.55f, 110f, 80f, f.highlight, width = 1.8f, alpha = 0.7f)
    }

    // Flares where the energy is brightest.
    rotate(f.spin * 0.7f, center) {
        flare(at(0f, f.radius * 0.87f), f.radius * 0.10f, f.highlight, 0.9f)
        flare(at(OrbMath.TAU * 0.42f, f.radius * 0.63f), f.radius * 0.07f, f.accent, 0.75f)
    }
    rotate(-f.spin * 0.5f, center) {
        flare(at(OrbMath.TAU * 0.7f, f.radius * 0.76f), f.radius * 0.06f, f.accent, 0.6f)
    }

    // Dust.
    particles(f, 40, 0.35f, 1.0f, f.accent, seed = 11, rotation = f.drift * 0.3f)

    // Dark well at the centre, as in the image — the logo sits in it.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent),
            center = center,
            radius = f.radius * 0.42f,
        ),
        radius = f.radius * 0.40f,
        center = center,
    )
    drawCircle(
        brush = Brush.radialGradient(
            listOf(f.accent.copy(alpha = 0.30f + f.amp * 0.4f), Color.Transparent),
            center = center,
            radius = f.radius * 0.30f * (0.9f + f.breathe * 0.12f),
        ),
        radius = f.radius * 0.26f,
        center = center,
    )
}

// --- 2. Lattice ----------------------------------------------------------

/**
 * Six translucent crystal prisms on a hexagon, joined by gold circuit-trace
 * strips, inside a dotted dome. The traces are right-angled on purpose: that is
 * what makes them read as printed circuitry rather than as wires.
 */
private fun DrawScope.drawLattice(f: OrbFrame) {
    bloom(f, f.accent, 0.98f)

    rotate(f.drift * 0.3f, center) {
        wireSphere(f.radius * 0.99f, f.accent, latitudes = 5, meridians = 9, alpha = 0.22f)
    }

    val ringR = f.radius * 0.60f
    val vertices = 6

    rotate(f.drift, center) {
        // Gold traces first, so the crystals sit on top of them.
        for (i in 0 until vertices) {
            val p1 = at(OrbMath.spokeAngle(i, vertices, -90f), ringR)
            val p2 = at(OrbMath.spokeAngle(i + 1, vertices, -90f), ringR)
            circuitTrace(p1, p2, f.highlight, strips = 3, spacing = 3.5f)
        }
        // Inward traces to the core, as in the image.
        for (i in 0 until vertices) {
            val p = at(OrbMath.spokeAngle(i, vertices, -90f), ringR)
            circuitTrace(p, center, f.highlight, strips = 2, spacing = 2.5f, alpha = 0.45f)
        }

        // A crystal at each vertex: an upper and lower facet, differently lit,
        // each showing its internal cut lines.
        for (i in 0 until vertices) {
            val a = OrbMath.spokeAngle(i, vertices, -90f)
            val c = at(a, ringR)
            val s = f.radius * (0.21f + f.amp * 0.03f)

            crystalFacet(
                Offset(c.x, c.y - s),
                Offset(c.x - s * 0.74f, c.y + s * 0.10f),
                Offset(c.x + s * 0.74f, c.y + s * 0.10f),
                fill = f.accent, edge = f.accent, fillAlpha = 0.62f,
            )
            crystalFacet(
                Offset(c.x, c.y + s * 0.95f),
                Offset(c.x - s * 0.74f, c.y + s * 0.10f),
                Offset(c.x + s * 0.74f, c.y + s * 0.10f),
                fill = f.secondary, edge = f.accent, fillAlpha = 0.50f,
            )
            flare(Offset(c.x, c.y - s * 0.9f), s * 0.30f, Color.White, 0.55f)
        }
    }

    // The dark hexagonal well at the centre.
    rotate(-f.counter * 0.4f, center) {
        val innerR = f.radius * 0.29f
        val hex = Path()
        for (i in 0 until 6) {
            val p = at(OrbMath.spokeAngle(i, 6, -90f), innerR)
            if (i == 0) hex.moveTo(p.x, p.y) else hex.lineTo(p.x, p.y)
        }
        hex.close()
        drawPath(hex, Color.Black.copy(alpha = 0.45f))
        drawPath(hex, f.accent.copy(alpha = 0.30f + f.amp * 0.30f), style = Stroke(px(1.6f)))
        dottedRing(innerR * 0.72f, f.accent, width = 1f, dotLength = 1.2f, gap = 3f)
    }
}

// --- 3. Prism ------------------------------------------------------------

/**
 * A low-poly faceted gem inside a strut-and-ball geodesic shell, with a gold
 * particle vortex at its core.
 */
private fun DrawScope.drawPrism(f: OrbFrame) {
    bloom(f, f.accent, 0.98f)

    rotate(f.drift * 0.3f, center) {
        geodesicShell(f.radius * 0.97f, f.highlight, segments = 18, alpha = 0.42f)
    }
    rotate(-f.drift * 0.2f, center) {
        wireSphere(f.radius * 0.80f, f.secondary, latitudes = 4, meridians = 7, alpha = 0.16f)
    }

    // Two latitude bands of alternating facets — a faceted sphere without a
    // real projection, which at this size is indistinguishable.
    val bands = listOf(0.64f to 14, 0.42f to 10)
    rotate(f.drift * 0.75f, center) {
        bands.forEachIndexed { bandIndex, (frac, facets) ->
            val outer = f.radius * frac
            val inner = f.radius * frac * 0.58f
            for (i in 0 until facets) {
                val a1 = OrbMath.spokeAngle(i, facets)
                val a2 = OrbMath.spokeAngle(i + 1, facets)
                val mid = (a1 + a2) / 2f
                val up = (i + bandIndex) % 2 == 0
                val fill = if (up) f.accent else f.highlight
                if (up) {
                    crystalFacet(at(mid, outer), at(a1, inner), at(a2, inner), fill, fill, 0.60f)
                } else {
                    crystalFacet(at(mid, inner), at(a1, outer), at(a2, outer), fill, fill, 0.44f)
                }
            }
        }
    }

    // Gold vortex spiralling into the middle.
    rotate(f.spin, center) {
        val arms = 3
        val perArm = 18
        for (arm in 0 until arms) {
            for (i in 0 until perArm) {
                val t = i.toFloat() / perArm
                val rad = OrbMath.spiralRadius(i, perArm, f.radius * 0.05f, f.radius * 0.32f)
                val a = OrbMath.spokeAngle(arm, arms) + t * 2.6f
                drawCircle(
                    color = f.highlight.copy(alpha = 0.9f - t * 0.6f),
                    radius = px(1.5f + (1f - t) * 0.8f),
                    center = at(a, rad),
                )
            }
        }
    }

    flare(center, f.radius * (0.15f + f.amp * 0.06f), f.highlight, 0.85f)
}

// --- 4. Forge (filigree) -------------------------------------------------

/**
 * Ornate copper filigree around a molten glass core: many fine dotted rings,
 * dense radial hatching and scalloped petal arcs, each layer drifting at its own
 * speed. The detail IS the design here — a few clean rings look like a dial.
 */
private fun DrawScope.drawFiligree(f: OrbFrame) {
    bloom(f, f.accent, 1.05f)

    // Fine concentric dotted rings, alternating direction.
    val rings = listOf(0.98f, 0.93f, 0.87f, 0.80f, 0.72f, 0.63f, 0.53f, 0.43f)
    rings.forEachIndexed { i, frac ->
        val rot = if (i % 2 == 0) f.drift * (0.8f + i * 0.16f) else -f.drift * (0.7f + i * 0.12f)
        rotate(rot, center) {
            dottedRing(
                f.radius * frac, f.accent,
                width = if (i % 3 == 0) 1.4f else 1f,
                dotLength = 1.2f + (i % 3) * 0.6f,
                gap = 3f,
            )
        }
    }

    // Dense hatching in two bands — the fine strips that make it read as
    // filigree rather than as a set of circles.
    rotate(f.counter * 0.35f, center) {
        radialHatch(f.radius * 0.80f, f.radius * 0.97f, 96, f.accent, alpha = 0.30f, emphasisEvery = 6)
    }
    rotate(-f.counter * 0.55f, center) {
        radialHatch(f.radius * 0.46f, f.radius * 0.62f, 64, f.highlight, alpha = 0.22f, emphasisEvery = 4)
    }

    // Scalloped petals: short arcs on three rings, the rose-window motif.
    listOf(0.90f to 16, 0.75f to 22, 0.58f to 28).forEachIndexed { i, (frac, petals) ->
        val rot = if (i % 2 == 0) f.drift * 1.6f else -f.drift * 1.2f
        rotate(rot, center) {
            val step = 360f / petals
            for (p in 0 until petals) {
                dottedArc(
                    f.radius * frac, p * step, step * 0.5f,
                    f.accent.copy(alpha = 0.75f), width = 2.2f, dotLength = 2f, gap = 2f,
                )
            }
        }
    }

    particles(f, 26, 0.5f, 0.95f, f.highlight, seed = 501, rotation = f.spin * 0.2f)

    // Molten glass core, lit from the upper left as in the design.
    val coreR = f.radius * 0.25f * (0.94f + f.breathe * 0.07f + f.amp * 0.18f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                f.highlight.copy(alpha = 0.95f),
                f.secondary.copy(alpha = 0.9f),
                f.secondary.copy(alpha = 0.25f),
                Color.Transparent,
            ),
            center = Offset(center.x - coreR * 0.3f, center.y - coreR * 0.35f),
            radius = coreR * 2.0f,
        ),
        radius = coreR,
        center = center,
    )
    drawCircle(f.accent.copy(alpha = 0.85f), coreR, center, style = Stroke(px(2f)))
    flare(Offset(center.x - coreR * 0.35f, center.y - coreR * 0.4f), coreR * 0.5f, Color.White, 0.5f)
}

// --- 5. Core (machine) ---------------------------------------------------

/**
 * A turning gear-and-iris core ringed by faceted crystal shards, inside a
 * strut-and-ball dome.
 */
private fun DrawScope.drawMachine(f: OrbFrame) {
    bloom(f, f.accent, 0.98f)

    rotate(f.drift * 0.25f, center) {
        geodesicShell(f.radius * 0.98f, f.secondary, segments = 20, alpha = 0.45f)
    }

    // Concentric machined rings.
    listOf(0.56f, 0.48f, 0.40f).forEachIndexed { i, frac ->
        rotate(if (i % 2 == 0) f.spin * 0.4f else -f.spin * 0.6f, center) {
            drawCircle(
                f.secondary.copy(alpha = 0.7f), f.radius * frac, center,
                style = Stroke(px(2.5f)),
            )
            radialHatch(f.radius * frac * 0.94f, f.radius * frac, 40, f.highlight, alpha = 0.35f)
        }
    }

    // The gear: teeth as short thick radial bars.
    val teeth = 24
    val gearR = f.radius * 0.60f
    rotate(f.spin * 0.45f, center) {
        for (i in 0 until teeth) {
            val a = OrbMath.spokeAngle(i, teeth)
            drawLine(
                color = f.secondary.copy(alpha = 0.92f),
                start = at(a, gearR),
                end = at(a, gearR * 1.14f),
                strokeWidth = px(6f),
            )
        }
        drawCircle(f.secondary.copy(alpha = 0.85f), gearR, center, style = Stroke(px(4f)))
        dottedRing(gearR * 0.92f, f.highlight, width = 1.2f, dotLength = 1.5f, gap = 3f)
    }

    // Iris blades at the very centre, turning against the gear.
    rotate(-f.spin * 0.9f, center) {
        val blades = 8
        val irisR = f.radius * 0.26f
        for (i in 0 until blades) {
            val a1 = OrbMath.spokeAngle(i, blades)
            val a2 = OrbMath.spokeAngle(i + 1, blades)
            val path = Path().apply {
                moveTo(center.x, center.y)
                lineTo(at(a1, irisR).x, at(a1, irisR).y)
                lineTo(at(a2, irisR * 0.55f).x, at(a2, irisR * 0.55f).y)
                close()
            }
            drawPath(path, f.highlight.copy(alpha = if (i % 2 == 0) 0.40f else 0.24f))
            drawPath(path, f.highlight.copy(alpha = 0.65f), style = Stroke(px(1f)))
        }
    }

    // Crystal shards ringing the machine.
    rotate(f.drift * 1.1f, center) {
        val shards = 11
        for (i in 0 until shards) {
            val a = OrbMath.spokeAngle(i, shards)
            val baseR = f.radius * 0.70f
            val tipR = baseR + f.radius * (0.18f + f.amp * 0.05f)
            val spread = 0.15f
            val fill = if (i % 2 == 0) f.accent else f.highlight
            crystalFacet(at(a, tipR), at(a - spread, baseR), at(a + spread, baseR), fill, f.accent, 0.62f)
        }
    }

    flare(center, f.radius * (0.10f + f.amp * 0.05f), Color.White, 0.75f)
}

// --- 6. Nebula -----------------------------------------------------------

/**
 * Spiral dust arms over a starfield, around a crystal flower, inside a coarse
 * copper wireframe.
 */
private fun DrawScope.drawNebula(f: OrbFrame) {
    bloom(f, f.accent, 1.10f)

    // Stars: fixed, so they hold still while the arms sweep past.
    particles(f, 60, 0.28f, 1.18f, Color.White, seed = 4001, rotation = 0f)

    // Dotted shells.
    listOf(0.96f, 0.82f, 0.66f).forEachIndexed { i, frac ->
        rotate(if (i % 2 == 0) f.drift * 0.7f else -f.drift * 0.5f, center) {
            dottedRing(f.radius * frac, f.accent, width = 1f, dotLength = 1.3f, gap = 4f)
        }
    }

    // Spiral arms — dense near the hub, thinning outward.
    val arms = 4
    val perArm = 30
    for (arm in 0 until arms) {
        rotate(f.spin * (0.45f + arm * 0.05f), center) {
            for (i in 0 until perArm) {
                val t = i.toFloat() / perArm
                val rad = OrbMath.spiralRadius(i, perArm, f.radius * 0.22f, f.radius * 1.04f)
                val a = OrbMath.spokeAngle(arm, arms) + t * 2.1f
                val color = if (arm % 2 == 0) f.accent else f.secondary
                drawCircle(
                    color = color.copy(alpha = 0.80f - t * 0.45f),
                    radius = px(1.2f + (1f - t) * 2.0f),
                    center = at(a, rad),
                )
            }
        }
    }

    // Coarse copper wireframe, as in the design's large triangles.
    rotate(-f.drift * 0.4f, center) {
        geodesicShell(f.radius * 0.94f, f.secondary, segments = 12, alpha = 0.38f)
    }

    // Crystal flower: pointed petals round a dark hub.
    rotate(f.counter * 0.55f, center) {
        val petals = 14
        val outerR = f.radius * (0.36f + f.amp * 0.04f)
        for (i in 0 until petals) {
            val a = OrbMath.spokeAngle(i, petals)
            val spread = 0.20f
            val fill = if (i % 2 == 0) f.accent else f.secondary
            crystalFacet(
                at(a, outerR), at(a - spread, f.radius * 0.16f), at(a + spread, f.radius * 0.16f),
                fill, f.highlight, if (i % 2 == 0) 0.75f else 0.50f,
            )
        }
    }

    val hubR = f.radius * 0.155f
    drawCircle(Color.Black.copy(alpha = 0.60f), hubR, center)
    drawCircle(f.highlight.copy(alpha = 0.85f), hubR, center, style = Stroke(px(2f)))
    dottedRing(hubR * 0.66f, f.highlight, width = 1f, dotLength = 1.2f, gap = 2.5f)
    flare(center, hubR * (0.8f + f.amp * 0.5f), f.highlight, 0.6f)
}

// --- shared dust ---------------------------------------------------------

/** Dust motes on a ring, deterministic so they drift rather than flicker. */
private fun DrawScope.particles(
    f: OrbFrame,
    count: Int,
    innerFraction: Float,
    outerFraction: Float,
    color: Color,
    seed: Int,
    rotation: Float,
) {
    rotate(rotation, center) {
        for (i in 0 until count) {
            val a = OrbMath.range(seed + i, 0f, OrbMath.TAU)
            val rad = f.radius * OrbMath.range(seed + i + 977, innerFraction, outerFraction)
            drawCircle(
                color = color.copy(alpha = OrbMath.range(seed + i + 55, 0.18f, 0.78f)),
                radius = px(OrbMath.range(seed + i + 313, 0.7f, 2.1f)),
                center = at(a, rad),
            )
        }
    }
}
