package com.jarvis.os.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jarvis.os.ui.theme.OrbStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One frame of orb animation: the values that move. */
data class OrbFrame(
    val radius: Float,
    val accent: Color,
    val secondary: Color,
    val highlight: Color,
    /** Degrees, the master clock. */
    val spin: Float,
    val drift: Float,
    val counter: Float,
    /** 0..1, eased, for the breathing core. */
    val breathe: Float,
    /** 0..1, live microphone level. */
    val amp: Float,
)

/**
 * Real 3D rings, drawn procedurally.
 *
 * Three approaches were tried and rejected before this one. Flat vector shapes
 * never resembled the references. Shipping the reference renders as sprites was
 * an exact likeness but could not move. Slicing those sprites into bands and
 * rotating each sheared them into hard-edged wedges, because a flat photograph
 * has no depth to turn through: "the rings don't look natural… absolutely
 * horrible."
 *
 * So the rings are now genuinely three-dimensional. Each is a circle in space
 * with its own tilt, precessing and spinning on its own clock, projected through
 * a perspective camera. Three things make that read as depth rather than as
 * wobbling ellipses:
 *
 *  - **Perspective.** The near side of a tilted ring projects larger than the far
 *    side. Orthographic projection loses exactly this and looks flat.
 *  - **Depth shading.** Every chunk of every ring takes its brightness and
 *    thickness from its own Z, so a ring visibly passes in front of and behind
 *    the core.
 *  - **Additive light.** Everything is blended with [BlendMode.Plus], so where
 *    two rings cross, the light sums and blooms — which is what the references
 *    do and what alpha compositing cannot fake.
 */
fun DrawScope.drawOrb3D(
    style: OrbStyle,
    f: OrbFrame,
    detail: OrbDetail,
    accent: Color,
    highlight: Color,
    secondary: Color,
) {
    val spec = specFor(style)
    val r = f.radius
    // From the spec file, not inline literals: `extentFor` measures whether a
    // theme fits using these exact numbers, and a copy that drifts would let the
    // fit check clear a ring that then clips.
    val camera = r * CAMERA_DISTANCE
    val focal = r * FOCAL
    // Radians: the master clock arrives in degrees.
    val t = f.drift * (Orb3D.TAU / 360f)

    // Bloom behind everything, so the orb sits in its own light.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                accent.copy(alpha = 0.20f + f.amp * 0.22f),
                accent.copy(alpha = 0.05f),
                Color.Transparent,
            ),
            center = center,
            radius = r * 1.15f,
        ),
        radius = r * 1.15f,
        center = center,
    )

    val breathe = 1f + f.breathe * 0.02f + f.amp * 0.05f

    spec.rings.forEach { ring ->
        drawRing(style, ring, r * breathe, camera, focal, t, detail, accent, highlight, f.amp)
    }

    drawMotes((spec.motes * detail.moteScale).toInt(), r * breathe, camera, focal, t, accent, highlight)

    if (spec.lobes > 0) {
        drawLobes(spec.lobes, r * breathe, t, accent, highlight, secondary, f.breathe)
    }

    if (spec.spokes > 0) {
        drawSpokes(spec.spokes, r * breathe, camera, focal, t, highlight)
    }

    // The centre goes on last and differs per theme — see [CoreKind]. Four sets
    // of rings around one identical glow is what made the themes read as
    // recolours of each other; the centre is the largest, brightest thing on
    // screen and it now says which theme this is on its own.
    val coreColour = ThemeArt.at(style, 0f)
    when (spec.core) {
        CoreKind.Spark -> drawSparkCore(spec.coreSize, r, coreColour, highlight, secondary, f)
        CoreKind.Molten -> drawMoltenCore(spec.coreSize, r, coreColour, accent, highlight, t, f)
        CoreKind.Diffuse -> drawDiffuseCore(spec.coreSize, r, coreColour, accent, highlight, t, f)
        CoreKind.World -> drawWorldCore(spec.coreSize, r, style, accent, highlight, t, f)
    }

    if (spec.embers > 0) {
        drawEmbers(spec.embers, r, spec.coreSize, t, highlight, accent)
    }

    // Beads last, over their own rings: they are the brightest thing on the
    // filigree and being occluded by the wire they run along would be wrong.
    if (spec.beads > 0) {
        drawBeads(spec, r * breathe, camera, focal, t, accent, highlight, f.amp)
    }
}

/**
 * One ring, drawn as a luminous shaded BAND rather than a stroked line.
 *
 * A stroke gives a wire; the references show broad swept ribbons of light whose
 * brightness falls off across their width and around their length. So the ring is
 * built from an inner and an outer edge in 3D and filled chunk by chunk, each
 * quad taking its own colour from its depth and its phase. Filled quads also
 * foreshorten correctly — the near side of the band is visibly wider than the far
 * side, which a constant-width stroke can never show.
 */
private fun DrawScope.drawRing(
    style: OrbStyle,
    ring: Ring3D,
    radius: Float,
    camera: Float,
    focal: Float,
    t: Float,
    detail: OrbDetail,
    accent: Color,
    highlight: Color,
    amp: Float,
) {
    // The colour the reference actually has at this radius, nudged toward the
    // theme's accent so the live state (thinking, error, speaking) still shows.
    val sampled = ThemeArt.at(style, ring.radius)
    val colour = lerpColor(sampled, lerpColor(accent, highlight, ring.warmth), 0.35f)
    val rr = radius * ring.radius
    val half = rr * ring.band
    // Precession: the tilt itself turns, which is what makes a ring read as a
    // gyroscope rather than a fixed ellipse with something sliding round it.
    val tiltX = ring.tiltX + sin(t * ring.precession) * PRECESS_SWING_X
    val tiltY = ring.tiltY + cos(t * ring.precession * 0.7f) * PRECESS_SWING_Y
    val spin = t * ring.spin

    val seg = detail.segments
    val outer = detail.outer
    val inner = detail.inner
    val mid = detail.mid
    Orb3D.ringInto(outer, rr + half, seg, tiltX, tiltY, spin, camera, focal)
    Orb3D.ringInto(inner, rr - half, seg, tiltX, tiltY, spin, camera, focal)
    Orb3D.ringInto(mid, rr, seg, tiltX, tiltY, spin, camera, focal)

    val per = seg / detail.chunks
    for (c in 0 until detail.chunks) {
        val a = c * per
        val b = minOf(a + per, seg)
        if (b <= a) continue

        val depth = Orb3D.depthFactor((mid[a * 3 + 2] + mid[b * 3 + 2]) / 2f, rr)
        val phase = Orb3D.wrap01((c.toFloat() / detail.chunks) + spin / Orb3D.TAU)
        // A long, soft falloff around the ring rather than a hard arc: the
        // references light most of the band, brightest at its head.
        val sweep = if (phase < ring.arc) (1f - phase / ring.arc) else 0f

        // The band itself: quad between the two edges, brightest near the camera.
        val quad = detail.scratch.apply {
            reset()
            moveTo(center.x + outer[a * 3], center.y + outer[a * 3 + 1])
            for (i in a..b) lineTo(center.x + outer[i * 3], center.y + outer[i * 3 + 1])
            for (i in b downTo a) lineTo(center.x + inner[i * 3], center.y + inner[i * 3 + 1])
            close()
        }

        val base = (0.05f + depth * 0.16f) * (0.55f + amp * 0.45f)
        drawPath(quad, colour.copy(alpha = base), blendMode = BlendMode.Plus)
        if (sweep > 0.01f) {
            drawPath(
                quad,
                colour.copy(alpha = sweep * (0.10f + depth * 0.30f)),
                blendMode = BlendMode.Plus,
            )
        }

        // A bright filament along the band's centre line — the hot core of the
        // ribbon, which is what stops a filled band looking like flat paint.
        // The filament reuses the same scratch Path, so it is drawn after the
        // band's fills are already committed.
        val line = detail.scratch.apply {
            reset()
            moveTo(center.x + mid[a * 3], center.y + mid[a * 3 + 1])
            for (i in a..b) lineTo(center.x + mid[i * 3], center.y + mid[i * 3 + 1])
        }
        drawPath(
            line,
            colour.copy(alpha = (0.18f + depth * 0.42f + sweep * 0.40f).coerceAtMost(1f)),
            style = Stroke(px(ring.width * (0.4f + depth * 0.7f)), cap = StrokeCap.Round),
            blendMode = BlendMode.Plus,
        )

        if (sweep > 0.88f && depth > 0.55f) {
            flare(
                Offset(center.x + mid[b * 3], center.y + mid[b * 3 + 1]),
                radius * 0.06f * depth,
                Color.White,
                0.45f * depth,
            )
        }
    }
}

/** Dust on a sphere, depth-shaded so the far motes sit behind the rings. */
private fun DrawScope.drawMotes(
    count: Int,
    radius: Float,
    camera: Float,
    focal: Float,
    t: Float,
    accent: Color,
    highlight: Color,
) {
    if (count <= 0) return
    Orb3D.spherePoints(count, radius * 0.98f).forEachIndexed { i, v ->
        val spun = Orb3D.rotateY(Orb3D.rotateX(v, t * 0.18f), t * 0.31f)
        val p = Orb3D.project(spun, camera, focal)
        val depth = Orb3D.depthFactor(p.depth, radius)
        drawCircle(
            color = (if (i % 5 == 0) highlight else accent)
                .copy(alpha = (0.10f + depth * 0.55f) * OrbMath.range(i * 7 + 3, 0.5f, 1f)),
            radius = px(0.7f + depth * 1.7f),
            center = Offset(center.x + p.x, center.y + p.y),
            blendMode = BlendMode.Plus,
        )
    }
}

/** Radial spokes in the hub, turning against the rings. */
private fun DrawScope.drawSpokes(
    count: Int,
    radius: Float,
    camera: Float,
    focal: Float,
    t: Float,
    highlight: Color,
) {
    val tilt = 0.9f
    for (i in 0 until count) {
        val a = (i.toFloat() / count) * Orb3D.TAU - t * 0.9f
        val inner = Orb3D.project(
            Orb3D.rotateX(Vec3(cos(a) * radius * 0.10f, sin(a) * radius * 0.10f, 0f), tilt),
            camera, focal,
        )
        val outer = Orb3D.project(
            Orb3D.rotateX(Vec3(cos(a) * radius * 0.30f, sin(a) * radius * 0.30f, 0f), tilt),
            camera, focal,
        )
        val depth = Orb3D.depthFactor(outer.depth, radius * 0.3f)
        drawLine(
            color = highlight.copy(alpha = 0.12f + depth * 0.45f),
            start = Offset(center.x + inner.x, center.y + inner.y),
            end = Offset(center.x + outer.x, center.y + outer.y),
            strokeWidth = px(0.8f + depth * 1.4f),
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * SPARK — the reactor's centre: a hard white point in a tight iris, answering
 * the microphone. Small, so the rings around it are the subject.
 */
private fun DrawScope.drawSparkCore(
    size: Float,
    radius: Float,
    accent: Color,
    highlight: Color,
    secondary: Color,
    f: OrbFrame,
) {
    val cr = radius * size * (0.92f + f.breathe * 0.08f + f.amp * 0.28f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = 0.85f),
                highlight.copy(alpha = 0.55f),
                accent.copy(alpha = 0.28f),
                Color.Transparent,
            ),
            center = center,
            radius = cr * 2.2f,
        ),
        radius = cr * 2.2f,
        center = center,
        blendMode = BlendMode.Plus,
    )
    drawCircle(
        color = secondary.copy(alpha = 0.22f),
        radius = cr * 0.9f,
        center = center,
        blendMode = BlendMode.Plus,
    )
    flare(center, cr * 1.5f, Color.White, 0.35f + f.amp * 0.35f)
}

/**
 * Points of light running along the rings.
 *
 * A filigree is fine metal with light caught in it. The six rings had the metal
 * and none of the light: thin, evenly lit circles that read as *drawn* rather
 * than as *made*. Each bead runs its own ring at its own rate, so the assembly
 * glitters continuously instead of pulsing in step, and each is depth-shaded
 * from the same projection the ring uses — so a bead visibly goes round the back
 * and comes out the other side rather than sliding along a flat ellipse.
 */
private fun DrawScope.drawBeads(
    spec: Orb3DSpec,
    radius: Float,
    camera: Float,
    focal: Float,
    t: Float,
    accent: Color,
    highlight: Color,
    amp: Float,
) {
    if (spec.rings.isEmpty()) return
    for (i in 0 until spec.beads) {
        val ring = spec.rings[i % spec.rings.size]
        val rr = radius * ring.radius
        // Its own offset round the ring, and its own rate, so no two beads on a
        // ring are ever level with each other.
        val a = OrbMath.unitRandom(i * 37 + 5) * Orb3D.TAU +
            t * ring.spin * OrbMath.range(i * 11 + 3, 0.75f, 1.45f)
        val tiltX = ring.tiltX + sin(t * ring.precession) * PRECESS_SWING_X
        val tiltY = ring.tiltY + cos(t * ring.precession * 0.7f) * PRECESS_SWING_Y
        val p = Orb3D.project(
            Orb3D.rotateY(
                Orb3D.rotateX(Vec3(cos(a) * rr, sin(a) * rr, 0f), tiltX),
                tiltY,
            ),
            camera, focal,
        )
        val depth = Orb3D.depthFactor(p.depth, rr)
        // Behind the orb they dim rather than disappear, which is what keeps the
        // ring reading as a continuous loop.
        val bright = (0.12f + depth * 0.78f) * (0.7f + amp * 0.3f)
        val at = Offset(center.x + p.x, center.y + p.y)
        val colour = lerpColor(accent, highlight, ring.warmth)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(colour.copy(alpha = bright * 0.55f), Color.Transparent),
                center = at,
                radius = px(2.6f) * (0.5f + depth),
            ),
            radius = px(2.6f) * (0.5f + depth),
            center = at,
            blendMode = BlendMode.Plus,
        )
        drawCircle(
            color = Color.White.copy(alpha = bright),
            radius = px(0.7f + depth * 0.9f),
            center = at,
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * MOLTEN — the forge's centre: a mass with a crust.
 *
 * The distinction that makes it read as *hot metal* rather than as a bright
 * light is that it has a SURFACE. A glow has no edge and no detail; this has a
 * dark rim, cracks that open across it, and a bright interior showing through
 * them. Everything is keyed off one slow clock so the crust seems to shift
 * rather than flicker.
 */
private fun DrawScope.drawMoltenCore(
    size: Float,
    radius: Float,
    deep: Color,
    accent: Color,
    highlight: Color,
    t: Float,
    f: OrbFrame,
) {
    val cr = radius * size * (0.94f + f.breathe * 0.06f + f.amp * 0.16f)

    // The body: bright at the centre, falling to a dark crust at the rim rather
    // than to transparency. The dark stop is what gives it an edge.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = 0.92f),
                highlight.copy(alpha = 0.80f),
                accent.copy(alpha = 0.55f),
                deep.copy(alpha = 0.75f),
            ),
            center = center,
            radius = cr,
        ),
        radius = cr,
        center = center,
    )

    // Cracks: bright seams across the crust, each drifting on its own clock so
    // the surface never repeats a pose.
    for (i in 0 until 9) {
        val a = OrbMath.range(i * 13 + 5, 0f, Orb3D.TAU) + sin(t * OrbMath.range(i * 7 + 2, 0.10f, 0.30f)) * 0.5f
        val len = cr * OrbMath.range(i * 11 + 3, 0.35f, 0.94f)
        val from = cr * OrbMath.range(i * 5 + 9, 0.05f, 0.30f)
        val heat = 0.30f + 0.45f * abs(sin(t * OrbMath.range(i * 3 + 1, 0.20f, 0.55f) + i))
        drawLine(
            brush = Brush.linearGradient(
                listOf(Color.Transparent, highlight.copy(alpha = heat), Color.Transparent),
                start = Offset(center.x + cos(a) * from, center.y + sin(a) * from),
                end = Offset(center.x + cos(a) * len, center.y + sin(a) * len),
            ),
            start = Offset(center.x + cos(a) * from, center.y + sin(a) * from),
            end = Offset(center.x + cos(a) * len, center.y + sin(a) * len),
            strokeWidth = px(0.9f + heat * 1.6f),
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
    }

    // The heat it throws, outside the body.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(highlight.copy(alpha = 0.30f + f.amp * 0.25f), Color.Transparent),
            center = center,
            radius = cr * 2.4f,
        ),
        radius = cr * 2.4f,
        center = center,
        blendMode = BlendMode.Plus,
    )
}

/**
 * DIFFUSE — the nebula's centre: layered gas with no surface anywhere.
 *
 * The opposite decision to [drawMoltenCore], and deliberately so. Five broad
 * overlapping veils, each offset a little from the middle and drifting on its
 * own clock, each ending in full transparency. Nothing here has an edge, which
 * is the whole identity of the theme — a nebula that resolves to a disc with a
 * rim is just a planet.
 */
private fun DrawScope.drawDiffuseCore(
    size: Float,
    radius: Float,
    deep: Color,
    accent: Color,
    highlight: Color,
    t: Float,
    f: OrbFrame,
) {
    val cr = radius * size * (1f + f.breathe * 0.10f + f.amp * 0.20f)
    for (i in 0 until 5) {
        val drift = t * OrbMath.range(i * 9 + 4, 0.08f, 0.22f) + i
        val off = cr * OrbMath.range(i * 7 + 1, 0.10f, 0.42f)
        val at = Offset(center.x + cos(drift) * off, center.y + sin(drift * 1.3f) * off * 0.7f)
        val veil = cr * OrbMath.range(i * 5 + 3, 0.85f, 1.9f)
        val colour = when (i % 3) {
            0 -> highlight
            1 -> accent
            else -> deep
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    colour.copy(alpha = 0.20f + f.amp * 0.10f),
                    colour.copy(alpha = 0.07f),
                    Color.Transparent,
                ),
                center = at,
                radius = veil,
            ),
            radius = veil,
            center = at,
            blendMode = BlendMode.Plus,
        )
    }
    // One small bright knot, so the cloud has somewhere to be looking at.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.55f), highlight.copy(alpha = 0.20f), Color.Transparent),
            center = center,
            radius = cr * 0.45f,
        ),
        radius = cr * 0.45f,
        center = center,
        blendMode = BlendMode.Plus,
    )
}

/**
 * WORLD — the ringed planet: a lit sphere with a terminator and a bright limb.
 *
 * The terminator is the whole thing. A flat disc with a radial glow reads as a
 * light source; a disc lit from one side, dark on the other, with a thin bright
 * edge where the atmosphere catches the sun, reads as a BODY — and the rings
 * around it stop being decoration and start being in orbit around something.
 */
private fun DrawScope.drawWorldCore(
    size: Float,
    radius: Float,
    style: OrbStyle,
    accent: Color,
    highlight: Color,
    t: Float,
    f: OrbFrame,
) {
    val cr = radius * size * (0.98f + f.breathe * 0.02f + f.amp * 0.08f)
    // Where the sun is. Turns very slowly, so the world is never posed the same
    // way twice but never appears to spin either.
    val sun = t * 0.06f
    val lit = Offset(center.x - cos(sun) * cr * 0.42f, center.y - sin(sun) * cr * 0.34f)

    // The body: bright on the sunward side, falling to near-black at the far limb.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                ThemeArt.at(style, 0.05f).copy(alpha = 0.98f),
                ThemeArt.at(style, 0.35f).copy(alpha = 0.85f),
                ThemeArt.at(style, 0.75f).copy(alpha = 0.55f),
                Color.Black.copy(alpha = 0.86f),
            ),
            center = lit,
            radius = cr * 1.55f,
        ),
        radius = cr,
        center = center,
    )

    // Cloud banding, squashed into latitudes so the sphere has a surface.
    for (i in 0 until 6) {
        val y = (i - 2.5f) / 3.2f
        val w = cr * kotlin.math.sqrt((1f - y * y).coerceAtLeast(0f))
        if (w <= 1f) continue
        drawLine(
            color = highlight.copy(alpha = 0.05f + 0.05f * abs(sin(t * 0.2f + i))),
            start = Offset(center.x - w, center.y + y * cr),
            end = Offset(center.x + w, center.y + y * cr),
            strokeWidth = px(1.4f),
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
    }

    // The limb: a thin bright rim on the sunward side only, fading round to
    // nothing. Drawn as short arcs so it can fade — a stroked circle cannot.
    for (i in 0 until 48) {
        val a = Orb3D.TAU * i / 48f
        val facing = ((cos(a - sun) + 1f) / 2f)
        if (facing < 0.35f) continue
        val edge = Offset(center.x + cos(a) * cr, center.y + sin(a) * cr)
        drawCircle(
            color = highlight.copy(alpha = (facing - 0.35f) * 0.85f),
            radius = px(1.1f),
            center = edge,
            blendMode = BlendMode.Plus,
        )
    }

    // The atmosphere it sits in.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.Transparent, accent.copy(alpha = 0.16f + f.amp * 0.14f), Color.Transparent),
            center = center,
            radius = cr * 1.35f,
        ),
        radius = cr * 1.35f,
        center = center,
        blendMode = BlendMode.Plus,
    )
}

/**
 * Gas lobes: broad soft billows around the centre, for the nebula.
 *
 * Flat 2D on purpose, unlike everything else in this file. A cloud has no
 * geometry to project — giving these depth-shaded 3D positions would make them
 * read as objects orbiting, which is precisely what a nebula is not.
 */
private fun DrawScope.drawLobes(
    count: Int,
    radius: Float,
    t: Float,
    accent: Color,
    highlight: Color,
    secondary: Color,
    breathe: Float,
) {
    for (i in 0 until count) {
        val orbit = radius * OrbMath.range(i * 11 + 7, 0.30f, 0.92f)
        val a = OrbMath.unitRandom(i * 5 + 2) * Orb3D.TAU +
            t * OrbMath.range(i * 3 + 8, 0.05f, 0.18f) * (if (i % 2 == 0) 1f else -1f)
        val at = Offset(center.x + cos(a) * orbit, center.y + sin(a) * orbit * 0.72f)
        val puff = radius * OrbMath.range(i * 7 + 4, 0.22f, 0.52f) * (0.92f + breathe * 0.16f)
        val colour = when (i % 3) {
            0 -> accent
            1 -> highlight
            else -> secondary
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    colour.copy(alpha = OrbMath.range(i * 13 + 1, 0.08f, 0.20f)),
                    colour.copy(alpha = 0.04f),
                    Color.Transparent,
                ),
                center = at,
                radius = puff,
            ),
            radius = puff,
            center = at,
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * Embers lifting off the forge.
 *
 * The only element in any theme that travels in one DIRECTION rather than round
 * the centre, which is what makes it read as heat rising instead of as more dust
 * in orbit. Each ember runs its own loop of the master clock, so they leave and
 * arrive continuously rather than as a pulse.
 */
private fun DrawScope.drawEmbers(
    count: Int,
    radius: Float,
    coreSize: Float,
    t: Float,
    highlight: Color,
    accent: Color,
) {
    for (i in 0 until count) {
        val speed = OrbMath.range(i * 7 + 3, 0.10f, 0.26f)
        // Its own phase through a rise, wrapped, so the field is never in step.
        val life = Orb3D.wrap01(OrbMath.unitRandom(i * 11 + 5) + t * speed)
        val lane = OrbMath.range(i * 5 + 1, -1f, 1f)
        // Rising and spreading, with a slow sideways wander.
        val up = radius * (coreSize * 0.7f + life * 1.05f)
        val across = radius * lane * (0.10f + life * 0.42f) +
            sin(t * 0.6f + i) * radius * 0.03f
        // Bright at the crust, gone by the top.
        val heat = (1f - life) * (1f - life)
        if (heat < 0.02f) continue
        val colour = if (i % 4 == 0) accent else highlight
        drawCircle(
            color = colour.copy(alpha = heat * OrbMath.range(i * 3 + 6, 0.35f, 0.85f)),
            radius = px(0.5f + heat * 1.5f),
            center = Offset(center.x + across, center.y - up),
            blendMode = BlendMode.Plus,
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * k,
        green = a.green + (b.green - a.green) * k,
        blue = a.blue + (b.blue - a.blue) * k,
        alpha = 1f,
    )
}
