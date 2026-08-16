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

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val focus = Offset(w / 2f, h * 0.34f)
        val base = ThemeArt.backdrop(palette.orbStyle)

        // Wash from behind the orb, in the reference's own background colour.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    base.copy(alpha = 0.42f),
                    base.copy(alpha = 0.16f),
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

        starField(w, h, palette.highlight, palette.orbStyle == OrbStyle.Nebula)
    }
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

/** Stars. Deterministic placement, so they drift rather than flicker. */
private fun DrawScope.starField(w: Float, h: Float, warm: Color, heavy: Boolean) {
    val count = if (heavy) 120 else 70
    for (i in 0 until count) {
        val x = OrbMath.unitRandom(i * 3 + 1) * w
        val y = OrbMath.unitRandom(i * 3 + 2) * h
        val alpha = OrbMath.range(i * 7 + 11, 0.10f, 0.50f)
        if (OrbMath.unitRandom(i * 17 + 7) > 0.95f) {
            flare(Offset(x, y), OrbMath.range(i, 3f, 7f) * density, Color.White, alpha * 1.3f)
        } else {
            drawCircle(
                color = (if (OrbMath.unitRandom(i * 13 + 5) > 0.74f) warm else Color.White)
                    .copy(alpha = alpha),
                radius = OrbMath.range(i * 3 + 3, 0.6f, 1.9f) * density,
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
