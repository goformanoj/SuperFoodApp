package com.jarvis.os.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jarvis.os.ui.theme.OrbStyle
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

/** One ring of the orb, in three dimensions. */
data class Ring3D(
    /** Fraction of the orb radius. */
    val radius: Float,
    /** Resting tilt about X and Y, radians. */
    val tiltX: Float,
    val tiltY: Float,
    /** How fast this ring precesses and spins, as multiples of the master clock. */
    val precession: Float,
    val spin: Float,
    /** 0 = accent, 1 = highlight; blended, so a ring can be any mix. */
    val warmth: Float,
    val width: Float,
    /** Fraction of the ring lit brightly — the travelling arc. 1f lights it all. */
    val arc: Float = 0.34f,
    /** Half-thickness of the luminous band, as a fraction of the ring's radius. */
    val band: Float = 0.10f,
)

/** Everything a theme needs to build its orb. */
data class Orb3DSpec(
    val rings: List<Ring3D>,
    val motes: Int,
    val coreSize: Float,
    /** Radial spokes inside the core, for the mechanical designs. */
    val spokes: Int = 0,
    /** Crystal shards standing on the widest ring. */
    val shards: Int = 0,
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
    val camera = r * 3.4f
    val focal = r * 2.55f
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

    if (spec.shards > 0) {
        drawShards(spec, r * breathe, camera, focal, t, detail, accent, highlight, f.amp)
    }

    drawMotes((spec.motes * detail.moteScale).toInt(), r * breathe, camera, focal, t, accent, highlight)

    if (spec.spokes > 0) {
        drawSpokes(spec.spokes, r * breathe, camera, focal, t, highlight)
    }

    drawCore(spec.coreSize, r, ThemeArt.at(style, 0f), highlight, secondary, f)
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
    val tiltX = ring.tiltX + sin(t * ring.precession) * 0.45f
    val tiltY = ring.tiltY + cos(t * ring.precession * 0.7f) * 0.55f
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

/** Crystal shards standing off the widest ring, for the faceted designs. */
private fun DrawScope.drawShards(
    spec: Orb3DSpec,
    radius: Float,
    camera: Float,
    focal: Float,
    t: Float,
    detail: OrbDetail,
    accent: Color,
    highlight: Color,
    amp: Float,
) {
    val base = spec.rings.maxOf { it.radius } * radius * 0.82f
    val tilt = 0.55f + sin(t * 0.4f) * 0.22f
    for (i in 0 until spec.shards) {
        val a = (i.toFloat() / spec.shards) * Orb3D.TAU + t * 0.5f
        val tip = Orb3D.rotateX(
            Vec3(cos(a) * base * 1.30f, sin(a) * base * 1.30f, 0f), tilt,
        )
        val l = Orb3D.rotateX(
            Vec3(cos(a - 0.16f) * base, sin(a - 0.16f) * base, 0f), tilt,
        )
        val rr = Orb3D.rotateX(
            Vec3(cos(a + 0.16f) * base, sin(a + 0.16f) * base, 0f), tilt,
        )
        val pt = Orb3D.project(tip, camera, focal)
        val pl = Orb3D.project(l, camera, focal)
        val pr = Orb3D.project(rr, camera, focal)
        val depth = Orb3D.depthFactor((pt.depth + pl.depth + pr.depth) / 3f, base)

        val path = detail.scratch.apply {
            reset()
            moveTo(center.x + pt.x, center.y + pt.y)
            lineTo(center.x + pl.x, center.y + pl.y)
            lineTo(center.x + pr.x, center.y + pr.y)
            close()
        }
        val colour = if (i % 2 == 0) accent else highlight
        drawPath(path, colour.copy(alpha = (0.10f + depth * 0.35f)), blendMode = BlendMode.Plus)
        drawPath(
            path,
            colour.copy(alpha = 0.25f + depth * 0.55f + amp * 0.15f),
            style = Stroke(px(0.6f + depth * 1.1f)),
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

/** The reactor core: a hot centre that answers the microphone. */
private fun DrawScope.drawCore(
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

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * k,
        green = a.green + (b.green - a.green) * k,
        blue = a.blue + (b.blue - a.blue) * k,
        alpha = 1f,
    )
}

/**
 * Per-theme geometry. The references differ in what their rings DO, so the specs
 * differ in tilt, count and precession rather than only in colour.
 */
// internal, not private, so the motion contract can be unit-tested. The same
// reason ScreenMatch and the Groq/Gemini parsers were widened: a value-in,
// value-out function that decides how something behaves should be reachable by a
// test rather than only by the eye.
internal fun specFor(style: OrbStyle): Orb3DSpec = when (style) {
    // Arc Reactor: many rings at steep opposing tilts, churning.
    OrbStyle.Reactor -> Orb3DSpec(
        rings = listOf(
            Ring3D(0.95f, 0.30f, 0.10f, 0.60f, 1.00f, 0.0f, 2.4f, 0.30f),
            Ring3D(0.80f, -0.55f, 0.40f, -0.85f, -1.35f, 0.9f, 2.8f, 0.26f),
            Ring3D(0.64f, 0.75f, -0.30f, 1.10f, 1.70f, 0.1f, 2.6f, 0.34f),
            Ring3D(0.48f, -0.25f, 0.80f, -1.30f, -2.10f, 0.8f, 2.2f, 0.40f),
            Ring3D(0.33f, 0.50f, 0.20f, 1.60f, 2.60f, 0.2f, 1.8f, 0.50f),
        ),
        motes = 60, coreSize = 0.17f,
    )
    // Lattice: few rings, near-rigid, with crystal shards on the widest.
    OrbStyle.Lattice -> Orb3DSpec(
        rings = listOf(
            Ring3D(0.96f, 0.22f, 0.14f, 0.30f, 0.55f, 0.0f, 2.0f, 0.24f),
            Ring3D(0.70f, -0.30f, 0.55f, -0.42f, -0.70f, 0.15f, 2.4f, 0.28f),
            Ring3D(0.44f, 0.60f, -0.20f, 0.55f, 0.95f, 0.85f, 2.0f, 0.36f),
        ),
        motes = 34, coreSize = 0.14f, shards = 6,
    )
    // Prism: a shell of shards round a tight, fast inner ring.
    OrbStyle.Prism -> Orb3DSpec(
        rings = listOf(
            Ring3D(0.98f, 0.18f, 0.28f, 0.38f, 0.60f, 0.9f, 1.8f, 0.22f),
            Ring3D(0.72f, -0.45f, -0.20f, -0.70f, -1.10f, 0.1f, 2.4f, 0.30f),
            Ring3D(0.40f, 0.65f, 0.45f, 1.20f, 2.00f, 0.75f, 2.2f, 0.44f),
        ),
        motes = 46, coreSize = 0.15f, shards = 10,
    )
    // Forge: many fine coplanar-ish rings, the busiest design.
    OrbStyle.Filigree -> Orb3DSpec(
        rings = listOf(
            Ring3D(0.98f, 0.14f, 0.06f, 0.22f, 0.75f, 0.15f, 1.6f, 0.20f),
            Ring3D(0.86f, -0.18f, 0.10f, -0.34f, -1.05f, 0.35f, 1.5f, 0.22f),
            Ring3D(0.72f, 0.24f, -0.12f, 0.48f, 1.35f, 0.10f, 1.7f, 0.26f),
            Ring3D(0.58f, -0.30f, 0.18f, -0.62f, -1.70f, 0.45f, 1.6f, 0.30f),
            Ring3D(0.44f, 0.36f, -0.22f, 0.80f, 2.10f, 0.20f, 1.5f, 0.36f),
            Ring3D(0.30f, -0.40f, 0.26f, -1.00f, -2.60f, 0.55f, 1.4f, 0.44f),
        ),
        motes = 40, coreSize = 0.19f, spokes = 24,
    )
    // Core: a machine — spokes in the hub, a firm outer cage.
    OrbStyle.Machine -> Orb3DSpec(
        rings = listOf(
            Ring3D(0.97f, 0.26f, 0.18f, 0.24f, 0.45f, 0.85f, 2.2f, 0.22f),
            Ring3D(0.66f, -0.50f, 0.30f, -0.55f, -1.60f, 0.15f, 3.0f, 0.30f),
            Ring3D(0.42f, 0.40f, -0.35f, 0.90f, 2.30f, 0.70f, 2.4f, 0.42f),
        ),
        motes = 30, coreSize = 0.13f, spokes = 16, shards = 8,
    )
    // Orbit: one bright world under wide ellipses swung right around it — and the
    // liveliest of the seven, deliberately.
    //
    // The reference is not an assembly of concentric rings like the other themes:
    // it is a single lit sphere with a few long orbital paths, several passing
    // well outside the body. So the core is the largest of any theme and the rings
    // are few, wide, and two of them sit beyond radius 1.0 because in the artwork
    // the widest orbits clearly clear the sphere.
    //
    // MOTION is the point here, so the speeds are not decorative — they follow
    // real orbital mechanics: the inner ring is the fastest and the widest is the
    // slowest, the way actual orbits behave. That single choice is what makes the
    // rings visibly separate, converge and cross rather than turning as one rigid
    // assembly, and it is why this reads as a system in motion instead of a
    // spinning graphic. Directions alternate so crossings are head-on, and every
    // period is a non-round multiple of its neighbour's, so the whole pattern
    // takes a long time to repeat.
    //
    // A first draft set `arc` to 0.80-0.92, which lit almost the whole ring and
    // left the travelling highlight with nowhere to travel — the least animated
    // theme of the seven, in the one design that is all about movement. The arcs
    // are ~0.5 now: enough unlit ring for a bright sweep to be seen running round
    // it, while depth shading keeps the rest of the ellipse continuous.
    OrbStyle.Orbit -> Orb3DSpec(
        rings = listOf(
            // Widest and slowest — the long outer orbit.
            Ring3D(1.26f, 0.30f, -0.16f, 0.42f, 0.62f, 0.00f, 1.7f, arc = 0.62f, band = 0.05f),
            // Counter-rotating, so it scissors against the one above.
            Ring3D(1.08f, -0.44f, 0.34f, -0.71f, -1.18f, 0.55f, 2.0f, arc = 0.58f, band = 0.06f),
            // Steeply tilted, faster again.
            Ring3D(0.90f, 0.64f, 0.10f, 1.13f, 1.79f, 0.85f, 1.6f, arc = 0.54f, band = 0.05f),
            // The tight HUD arc hugging the body: innermost, fastest, counter-spun.
            Ring3D(0.70f, 0.09f, 0.05f, -1.67f, -2.63f, 0.20f, 1.2f, arc = 0.46f, band = 0.04f),
        ),
        // The heaviest dust field of any theme — it is a scene in space, and the
        // motes are what make the volume around the sphere read as occupied.
        motes = 120, coreSize = 0.34f,
    )
    // Nebula: wide sweeping rings and a heavy star field.
    OrbStyle.Nebula -> Orb3DSpec(
        rings = listOf(
            Ring3D(1.00f, 0.34f, 0.22f, 0.45f, 0.70f, 0.0f, 2.0f, 0.28f),
            Ring3D(0.78f, -0.60f, 0.44f, -0.62f, -1.00f, 0.65f, 2.6f, 0.32f),
            Ring3D(0.52f, 0.70f, -0.36f, 0.95f, 1.55f, 0.25f, 2.2f, 0.40f),
        ),
        motes = 90, coreSize = 0.16f,
    )
}
