package com.jarvis.os.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.OrbStyle

/**
 * The world each theme's orb sits in.
 *
 * This used to be one generic star field recoloured six ways, which is why the
 * themes still read as the same screen in different accents — every reference
 * puts something specific behind the orb, and that is half of what makes them
 * distinct. Each theme now gets its own: a dotted wireframe globe for the two
 * blue designs, a strut-and-ball geodesic shell for the faceted ones, HUD
 * brackets and warm haze for the forge, a nebula and circuit floor for the last.
 *
 * The base colour is measured from the corners of each reference render rather
 * than chosen, so the backdrop starts from the source's own light.
 */
@Composable
fun ThemeBackdrop(
    modifier: Modifier = Modifier,
    palette: JarvisPalette = LocalPalette.current,
) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    // Very slow. A backdrop that visibly turns competes with the orb; one that
    // drifts imperceptibly is atmosphere.
    val drift by transition.animateFloat(
        0f, Orb3D.TAU,
        infiniteRepeatable(tween(150_000, easing = LinearEasing)),
        label = "backdropDrift",
    )
    // A second, much faster clock. The aurora needs to be seen to move — the
    // 150-second drift above is deliberately imperceptible, and running the
    // clouds on it produced a backdrop nobody could tell was animated at all.
    val flow by transition.animateFloat(
        0f, Orb3D.TAU,
        infiniteRepeatable(tween(38_000, easing = LinearEasing)),
        label = "backdropFlow",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val focus = Offset(w / 2f, h * 0.34f)
        val base = ThemeArt.backdrop(palette.orbStyle)

        // ── Depth ────────────────────────────────────────────────────────────
        // A vertical ramp under everything. Without it the screen is one flat
        // colour with objects sitting ON it; with it there is a top and a bottom,
        // which is most of what made the old backdrop read as dull.
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    base.copy(alpha = 0.34f),
                    Color.Transparent,
                    base.copy(alpha = 0.22f),
                ),
            ),
        )

        // ── Aurora ───────────────────────────────────────────────────────────
        // Four large soft blooms drifting across each other on slow lissajous
        // paths, added rather than painted over, so where two overlap the colour
        // genuinely brightens. This is the layer doing the work: one flat wash
        // reads as a background, several moving ones read as a place.
        aurora(w, h, flow, palette.accent, palette.secondary, palette.highlight)

        // Wash from behind the orb, in the reference's own background colour.
        // Stronger than it was: it now has an aurora to sit in front of.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    base.copy(alpha = 0.58f),
                    base.copy(alpha = 0.24f),
                    Color.Transparent,
                ),
                center = focus,
                radius = maxOf(w, h) * 0.8f,
            ),
        )

        when (palette.orbStyle) {
            OrbStyle.Reactor, OrbStyle.Lattice ->
                wireGlobe(focus, minOf(w, h) * 0.62f, drift, palette.accent, dense = palette.orbStyle == OrbStyle.Lattice)

            OrbStyle.Prism, OrbStyle.Machine -> {
                nodeShell(focus, minOf(w, h) * 0.60f, drift, palette.secondary)
                groundMesh(h * 0.64f, palette.secondary, rows = 10, columns = 16, alpha = 0.20f)
            }

            OrbStyle.Filigree -> {
                warmHaze(focus, minOf(w, h) * 0.70f, palette.accent, palette.highlight)
                hudBrackets(palette.accent)
            }

            OrbStyle.Nebula -> {
                nebulaClouds(w, h, palette.accent, palette.secondary)
                circuitFloor(h * 0.66f, palette.accent)
            }

            OrbStyle.Orbit -> {
                // The element no other theme has: a planet limb across the bottom,
                // lit along its edge, with city lights on the dark side. It is what
                // puts the orb in space rather than on a background, and it is the
                // thing the eye reads first after the orb itself.
                planetHorizon(w, h, palette.accent, palette.highlight)
                hudBrackets(palette.accent)
            }
        }

        starField(w, h, palette.highlight, palette.orbStyle == OrbStyle.Nebula, flow)

        // A light source below the fold. Orbit already has a lit planet down
        // there and a second glow would fight it.
        if (palette.orbStyle != OrbStyle.Orbit) horizonGlow(w, h, palette.accent, palette.secondary)

        // Framed last. A vignette is what stops a bright backdrop competing with
        // the text on top of it — the corners fall away and the eye goes to the
        // orb, which is the only reason the brightness is affordable at all.
        vignette(w, h)
    }
}

/**
 * Slow overlapping colour fields — the layer that turns a flat ground into
 * weather.
 *
 * Additive on purpose ([BlendMode.Plus]): painted normally, four translucent
 * circles just average toward mud, and the whole point is that the overlaps are
 * the brightest part. Alphas are low for the same reason — with Plus, four of
 * them stack, and values that look right alone blow out where they cross.
 *
 * Positions come from sin/cos of one clock rather than anything remembered, so
 * this stays a pure function of time and costs no state.
 */
private fun DrawScope.aurora(
    w: Float,
    h: Float,
    t: Float,
    accent: Color,
    secondary: Color,
    highlight: Color,
) {
    val span = maxOf(w, h)
    val blooms = listOf(
        Triple(accent, Offset(w * (0.26f + 0.10f * kotlin.math.sin(t)), h * (0.20f + 0.06f * kotlin.math.cos(t * 0.7f))), 0.62f),
        Triple(secondary, Offset(w * (0.78f + 0.09f * kotlin.math.cos(t * 0.8f)), h * (0.33f + 0.07f * kotlin.math.sin(t * 1.1f))), 0.70f),
        Triple(highlight, Offset(w * (0.60f + 0.12f * kotlin.math.sin(t * 0.6f + 2f)), h * (0.72f + 0.05f * kotlin.math.cos(t))), 0.52f),
        Triple(accent, Offset(w * (0.14f + 0.08f * kotlin.math.cos(t * 1.3f)), h * (0.86f + 0.04f * kotlin.math.sin(t * 0.9f))), 0.46f),
    )
    blooms.forEach { (colour, centre, scale) ->
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    colour.copy(alpha = 0.20f),
                    colour.copy(alpha = 0.07f),
                    Color.Transparent,
                ),
                center = centre,
                radius = span * scale,
            ),
            blendMode = BlendMode.Plus,
        )
    }
}

/** A band of light along the bottom edge, as if something is lit off-screen. */
private fun DrawScope.horizonGlow(w: Float, h: Float, accent: Color, secondary: Color) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                secondary.copy(alpha = 0.26f),
                accent.copy(alpha = 0.10f),
                Color.Transparent,
            ),
            center = Offset(w * 0.5f, h * 1.06f),
            radius = maxOf(w, h) * 0.62f,
        ),
        blendMode = BlendMode.Plus,
    )
}

/** Darkened corners, so the bright middle stays the subject. */
private fun DrawScope.vignette(w: Float, h: Float) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = 0.30f),
                Color.Black.copy(alpha = 0.62f),
            ),
            center = Offset(w * 0.5f, h * 0.42f),
            radius = maxOf(w, h) * 0.78f,
        ),
    )
}

/** A dotted wireframe globe in real 3D — latitudes and meridians, slowly turning. */
private fun DrawScope.wireGlobe(
    centre: Offset,
    radius: Float,
    t: Float,
    colour: Color,
    dense: Boolean,
) {
    val dots = PathEffect.dashPathEffect(floatArrayOf(px(1.4f), px(5f)))
    val cam = radius * 3.6f
    val focal = radius * 2.7f
    val lats = if (dense) 7 else 5
    val meridians = if (dense) 12 else 9

    for (i in 1..lats) {
        val phi = (i.toFloat() / (lats + 1)) * Orb3D.TAU / 2f - Orb3D.TAU / 4f
        val ringR = radius * kotlin.math.cos(phi)
        val yOff = radius * kotlin.math.sin(phi)
        val pts = Orb3D.ring(ringR, 64, Orb3D.TAU / 4f, 0f, t * 0.4f)
            .map { Orb3D.project(Vec3(it.x, it.z + yOff, it.y), cam, focal) }
        strokeProjected(pts, centre, colour.copy(alpha = 0.20f), 1f, dots)
    }
    for (m in 0 until meridians) {
        val pts = Orb3D.ring(radius, 64, 0f, (m.toFloat() / meridians) * Orb3D.TAU + t * 0.4f, 0f)
            .map { Orb3D.project(it, cam, focal) }
        strokeProjected(pts, centre, colour.copy(alpha = 0.13f), 1f, dots)
    }
}

/** A strut-and-ball geodesic shell, as behind the faceted designs. */
private fun DrawScope.nodeShell(centre: Offset, radius: Float, t: Float, colour: Color) {
    val cam = radius * 3.6f
    val focal = radius * 2.7f
    val rings = 4
    for (i in 1..rings) {
        val phi = (i.toFloat() / (rings + 1)) * Orb3D.TAU / 2f - Orb3D.TAU / 4f
        val ringR = radius * kotlin.math.cos(phi)
        val yOff = radius * kotlin.math.sin(phi)
        val nodes = 14
        val pts = (0 until nodes).map { n ->
            val a = (n.toFloat() / nodes) * Orb3D.TAU + t * 0.35f
            Orb3D.project(
                Vec3(kotlin.math.cos(a) * ringR, yOff, kotlin.math.sin(a) * ringR),
                cam, focal,
            )
        }
        pts.forEachIndexed { n, p ->
            val q = pts[(n + 1) % pts.size]
            val depth = Orb3D.depthFactor(p.depth, radius)
            drawLine(
                colour.copy(alpha = 0.06f + depth * 0.16f),
                Offset(centre.x + p.x, centre.y + p.y),
                Offset(centre.x + q.x, centre.y + q.y),
                px(1f),
            )
            drawCircle(
                colour.copy(alpha = 0.14f + depth * 0.32f),
                px(1.6f + depth * 1.4f),
                Offset(centre.x + p.x, centre.y + p.y),
            )
        }
    }
}

/** Warm concentric haze for the forge. */
private fun DrawScope.warmHaze(centre: Offset, radius: Float, accent: Color, highlight: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            listOf(highlight.copy(alpha = 0.10f), accent.copy(alpha = 0.05f), Color.Transparent),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
        blendMode = BlendMode.Plus,
    )
    val dots = PathEffect.dashPathEffect(floatArrayOf(px(1.2f), px(6f)))
    listOf(0.62f, 0.78f, 0.94f).forEachIndexed { i, f ->
        drawCircle(
            colour(accent, 0.09f - i * 0.02f),
            radius * f,
            centre,
            style = Stroke(px(1f), pathEffect = dots),
        )
    }
}

/** Corner brackets, as in the forge reference's HUD panels. */
private fun DrawScope.hudBrackets(colour: Color) {
    val w = size.width
    val h = size.height
    val len = minOf(w, h) * 0.07f
    val inset = len * 0.5f
    val c = colour.copy(alpha = 0.22f)
    listOf(
        Triple(Offset(inset, inset), Offset(1f, 0f), Offset(0f, 1f)),
        Triple(Offset(w - inset, inset), Offset(-1f, 0f), Offset(0f, 1f)),
        Triple(Offset(inset, h - inset), Offset(1f, 0f), Offset(0f, -1f)),
        Triple(Offset(w - inset, h - inset), Offset(-1f, 0f), Offset(0f, -1f)),
    ).forEach { (p, dx, dy) ->
        drawLine(c, p, Offset(p.x + dx.x * len, p.y), px(1.4f), cap = StrokeCap.Round)
        drawLine(c, p, Offset(p.x, p.y + dy.y * len), px(1.4f), cap = StrokeCap.Round)
    }
}

/** Soft nebula clouds, deterministic so they do not swim between frames. */
private fun DrawScope.nebulaClouds(w: Float, h: Float, accent: Color, secondary: Color) {
    for (i in 0 until 5) {
        val x = OrbMath.range(i * 31 + 5, 0.1f, 0.9f) * w
        val y = OrbMath.range(i * 31 + 9, 0.05f, 0.85f) * h
        val r = OrbMath.range(i * 31 + 13, 0.18f, 0.42f) * minOf(w, h)
        val c = if (i % 2 == 0) accent else secondary
        drawCircle(
            brush = Brush.radialGradient(
                listOf(c.copy(alpha = 0.075f), Color.Transparent),
                center = Offset(x, y),
                radius = r,
            ),
            radius = r,
            center = Offset(x, y),
            blendMode = BlendMode.Plus,
        )
    }
}

/** A circuit-board floor, as under the nebula reference. */
private fun DrawScope.circuitFloor(horizonY: Float, colour: Color) {
    groundMesh(horizonY, colour, rows = 9, columns = 14, alpha = 0.14f)
    val w = size.width
    val h = size.height
    // Right-angled traces running along the floor.
    for (i in 0 until 7) {
        val y = horizonY + (h - horizonY) * OrbMath.range(i * 17 + 3, 0.15f, 0.95f)
        val x0 = OrbMath.range(i * 17 + 7, 0f, 0.6f) * w
        val x1 = x0 + OrbMath.range(i * 17 + 11, 0.15f, 0.4f) * w
        val drop = OrbMath.range(i * 17 + 19, 0.02f, 0.06f) * h
        val path = Path().apply {
            moveTo(x0, y)
            lineTo(x1, y)
            lineTo(x1, y + drop)
            lineTo(x1 + w * 0.12f, y + drop)
        }
        drawPath(path, colour.copy(alpha = 0.16f), style = Stroke(px(1.2f)))
    }
}

/**
 * Stars.
 *
 * Placement is deterministic ([OrbMath.unitRandom], never `Math.random`) for the
 * reason this project has already paid for once: a Canvas redraws every frame, so
 * anything deciding WHERE a star sits is asked sixty times a second, and a real
 * random re-scatters the sky into static.
 *
 * Brightness is the opposite — it is *meant* to change, so it takes the clock.
 * Each star gets its own phase from its index, so they breathe out of step
 * instead of the whole field pulsing as one. That is the difference between a sky
 * and a string of fairy lights.
 */
private fun DrawScope.starField(w: Float, h: Float, warm: Color, heavy: Boolean, t: Float) {
    val count = if (heavy) 220 else 150
    for (i in 0 until count) {
        val x = OrbMath.unitRandom(i * 3 + 1) * w
        val y = OrbMath.unitRandom(i * 3 + 2) * h
        // Own phase, own rate. Shallow: a star that fades to nothing reads as a
        // rendering fault rather than as distance.
        val twinkle = 0.78f + 0.22f * kotlin.math.sin(t * (1.4f + OrbMath.unitRandom(i * 5 + 2) * 2.2f) + i)
        val alpha = OrbMath.range(i * 7 + 11, 0.22f, 0.85f) * twinkle
        if (OrbMath.unitRandom(i * 17 + 7) > 0.93f) {
            flare(Offset(x, y), OrbMath.range(i, 3f, 9f) * density, Color.White, alpha * 1.25f)
        } else {
            drawCircle(
                color = (if (OrbMath.unitRandom(i * 13 + 5) > 0.68f) warm else Color.White)
                    .copy(alpha = alpha),
                radius = OrbMath.range(i * 3 + 3, 0.6f, 2.3f) * density,
                center = Offset(x, y),
            )
        }
    }
}

private fun DrawScope.strokeProjected(
    pts: List<Projected>,
    centre: Offset,
    colour: Color,
    width: Float,
    effect: PathEffect?,
) {
    val path = Path()
    pts.forEachIndexed { i, p ->
        val x = centre.x + p.x
        val y = centre.y + p.y
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, colour, style = Stroke(px(width), pathEffect = effect))
}

private fun colour(c: Color, alpha: Float) = c.copy(alpha = alpha)

/**
 * A planet's limb across the bottom of the screen: the curve of a world, a thin
 * lit atmosphere along its edge, and scattered city lights on the dark side.
 *
 * Drawn as a circle far larger than the screen whose top edge crosses the lower
 * third, which is what gives the shallow, almost-straight curve of a horizon seen
 * from orbit — a small circle would read as a ball sitting on the screen.
 *
 * The lights are placed from [OrbMath.unitRandom] rather than a random, for the
 * reason this project has already paid for once: a Canvas redraws every frame, so
 * anything deciding WHERE a light sits is asked sixty times a second, and a real
 * random re-scatters them into static instead of a city.
 */
private fun DrawScope.planetHorizon(w: Float, h: Float, edge: Color, warm: Color) {
    // A very large circle whose top sits just below the waveform.
    val horizonY = h * 0.82f
    val planetRadius = w * 2.6f
    val centre = Offset(w * 0.5f, horizonY + planetRadius)

    // The dark body itself, so stars do not shine through the planet.
    drawCircle(color = Color(0xFF01050C), radius = planetRadius, center = centre)

    // City lights: only just inside the limb, where a night side is actually lit.
    val lights = 140
    for (i in 0 until lights) {
        val a = OrbMath.unitRandom(i * 3 + 1)          // along the limb
        val d = OrbMath.unitRandom(i * 3 + 2)          // depth below it
        val b = OrbMath.unitRandom(i * 3 + 3)          // brightness
        val angle = (-0.5f + a) * 1.15f            // radians either side of straight up
        val depth = planetRadius * (0.002f + d * 0.05f)
        val x = centre.x + kotlin.math.sin(angle) * (planetRadius - depth)
        val y = centre.y - kotlin.math.cos(angle) * (planetRadius - depth)
        if (y < horizonY - 4f || y > h) continue
        drawCircle(
            color = warm.copy(alpha = 0.10f + b * 0.45f),
            radius = 0.7f + b * 1.5f,
            center = Offset(x, y),
        )
    }

    // The atmosphere: a bright hairline on the limb over a soft outward bloom.
    drawCircle(
        color = edge.copy(alpha = 0.10f),
        radius = planetRadius + h * 0.020f,
        center = centre,
        style = Stroke(width = h * 0.040f),
    )
    drawCircle(
        color = edge.copy(alpha = 0.30f),
        radius = planetRadius + h * 0.004f,
        center = centre,
        style = Stroke(width = h * 0.010f),
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.55f),
        radius = planetRadius,
        center = centre,
        style = Stroke(width = 1.6f),
    )
}
