package com.jarvis.os.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * The drawing vocabulary the six themes are built from.
 *
 * The first attempt at these themes drew clean solid arcs and looked like a
 * generic HUD rather than the designs. What actually characterises the
 * reference images is texture: rings are fine DOTTED light strips, not strokes;
 * there are real wireframe spheres with latitude and meridian lines; bright
 * points throw four-point lens flares; crystals show their internal facet lines.
 * Those are the primitives, so each style can be assembled from them instead of
 * approximated.
 *
 * Dotted rings use a dash path effect rather than a loop of circles. A ring of
 * sixty dots drawn individually is sixty draw calls, and six such rings per
 * frame per orb is where the framerate goes; a dashed stroke is one call and
 * looks the same.
 */

/** Stroke widths in dp — a raw pixel width is hairline on a dense screen. */
internal fun DrawScope.px(dp: Float): Float = dp * density

/**
 * A ring drawn as a fine dotted light strip — the texture that recurs in every
 * one of the designs.
 *
 * [dotLength] and [gap] are in dp, so the dot density stays constant across
 * screen densities instead of turning solid on a dense one.
 */
internal fun DrawScope.dottedRing(
    radius: Float,
    color: Color,
    width: Float = 1f,
    dotLength: Float = 1.5f,
    gap: Float = 4f,
    centre: Offset = center,
) {
    drawCircle(
        color = color,
        radius = radius,
        center = centre,
        style = Stroke(
            width = px(width),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(px(dotLength), px(gap))),
        ),
    )
}

/** A dotted arc, for the partial bands in the Reactor and Forge designs. */
internal fun DrawScope.dottedArc(
    radius: Float,
    startAngle: Float,
    sweep: Float,
    color: Color,
    width: Float = 1f,
    dotLength: Float = 1.5f,
    gap: Float = 4f,
) {
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(
            width = px(width),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(px(dotLength), px(gap))),
        ),
    )
}

/**
 * A wireframe globe: latitude ellipses stacked up the sphere plus meridian
 * ellipses through the poles.
 *
 * This is the shape behind four of the six designs, and it is what a flat circle
 * cannot fake — the give-away is that latitudes bunch toward the poles while
 * meridians narrow toward the centre.
 */
internal fun DrawScope.wireSphere(
    radius: Float,
    color: Color,
    latitudes: Int = 5,
    meridians: Int = 8,
    alpha: Float = 0.30f,
    dotted: Boolean = true,
    nodes: Boolean = false,
    nodeColor: Color = color,
) {
    val effect = if (dotted) {
        PathEffect.dashPathEffect(floatArrayOf(px(1.5f), px(4f)))
    } else {
        null
    }

    // Latitudes: circles of shrinking radius, offset up and down the sphere.
    for (i in 1..latitudes) {
        val t = i.toFloat() / (latitudes + 1)
        val phi = t * OrbMath.TAU / 2f - OrbMath.TAU / 4f   // -90°..+90°
        val ringR = radius * cos(phi)
        val yOff = radius * sin(phi)
        // Flattened vertically: a latitude seen edge-on is an ellipse, not a circle.
        val ringRy = ringR * 0.26f
        drawOval(
            color = color.copy(alpha = alpha),
            topLeft = Offset(center.x - ringR, center.y + yOff - ringRy),
            size = Size(ringR * 2f, ringRy * 2f),
            style = Stroke(px(1f), pathEffect = effect),
        )
        if (nodes) {
            for (m in 0 until meridians) {
                val a = OrbMath.spokeAngle(m, meridians)
                drawCircle(
                    color = nodeColor.copy(alpha = alpha * 2.2f),
                    radius = px(1.8f),
                    center = Offset(
                        center.x + cos(a) * ringR,
                        center.y + yOff + sin(a) * ringRy,
                    ),
                )
            }
        }
    }

    // Meridians: ellipses through the poles, narrowing toward the centre.
    for (m in 0 until meridians) {
        val t = m.toFloat() / meridians
        val rx = radius * cos(t * OrbMath.TAU / 2f)
        drawOval(
            color = color.copy(alpha = alpha * 0.8f),
            topLeft = Offset(center.x - kotlin.math.abs(rx), center.y - radius),
            size = Size(kotlin.math.abs(rx) * 2f, radius * 2f),
            style = Stroke(px(1f), pathEffect = effect),
        )
    }

    // The silhouette.
    drawCircle(
        color = color.copy(alpha = alpha * 1.4f),
        radius = radius,
        center = center,
        style = Stroke(px(1.2f)),
    )
}

/**
 * A geodesic shell: nodes on a ring joined to their neighbours and to a second,
 * smaller ring — the strut-and-ball frame in the Prism and Core designs.
 */
internal fun DrawScope.geodesicShell(
    radius: Float,
    color: Color,
    segments: Int = 16,
    alpha: Float = 0.5f,
) {
    val inner = radius * 0.74f
    for (i in 0 until segments) {
        val a1 = OrbMath.spokeAngle(i, segments)
        val a2 = OrbMath.spokeAngle(i + 1, segments)
        val half = OrbMath.spokeAngle(i, segments, 360f / segments / 2f)

        val outer1 = Offset(center.x + cos(a1) * radius, center.y + sin(a1) * radius)
        val outer2 = Offset(center.x + cos(a2) * radius, center.y + sin(a2) * radius)
        val innerP = Offset(center.x + cos(half) * inner, center.y + sin(half) * inner)

        drawLine(color.copy(alpha = alpha), outer1, outer2, px(1f))
        drawLine(color.copy(alpha = alpha * 0.7f), outer1, innerP, px(1f))
        drawLine(color.copy(alpha = alpha * 0.7f), outer2, innerP, px(1f))
        // Node balls, as in the designs.
        drawCircle(color.copy(alpha = alpha * 1.8f), px(2.6f), outer1)
        drawCircle(color.copy(alpha = alpha), px(1.6f), innerP)
    }
}

/**
 * A four-point lens flare. Every design puts these where energy is brightest,
 * and they are most of what makes a bright dot read as a light source.
 */
internal fun DrawScope.flare(at: Offset, size: Float, color: Color, alpha: Float = 1f) {
    drawCircle(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = alpha), Color.Transparent),
            center = at,
            radius = size,
        ),
        radius = size,
        center = at,
    )
    drawCircle(Color.White.copy(alpha = alpha * 0.9f), size * 0.16f, at)
    // The cross: long horizontal, shorter vertical, both tapering to nothing.
    for ((dx, dy, len) in listOf(
        Triple(1f, 0f, size * 2.6f),
        Triple(0f, 1f, size * 1.5f),
    )) {
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, color.copy(alpha = alpha * 0.85f), Color.Transparent),
                start = Offset(at.x - dx * len, at.y - dy * len),
                end = Offset(at.x + dx * len, at.y + dy * len),
            ),
            start = Offset(at.x - dx * len, at.y - dy * len),
            end = Offset(at.x + dx * len, at.y + dy * len),
            strokeWidth = px(1.6f),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Dense fine radial lines between two radii — the hatched texture inside the
 * Forge rings and behind the Reactor bands. Every [emphasisEvery]-th line is
 * brighter, which is what stops it reading as a flat grey band.
 */
internal fun DrawScope.radialHatch(
    innerRadius: Float,
    outerRadius: Float,
    count: Int,
    color: Color,
    alpha: Float = 0.35f,
    emphasisEvery: Int = 4,
    width: Float = 1f,
) {
    for (i in 0 until count) {
        val a = OrbMath.spokeAngle(i, count)
        val strong = i % emphasisEvery == 0
        drawLine(
            color = color.copy(alpha = if (strong) alpha * 2.2f else alpha),
            start = Offset(center.x + cos(a) * innerRadius, center.y + sin(a) * innerRadius),
            end = Offset(center.x + cos(a) * outerRadius, center.y + sin(a) * outerRadius),
            strokeWidth = px(if (strong) width * 1.5f else width),
        )
    }
}

/**
 * A gold circuit trace: an L-bent path drawn as parallel strips, as the traces
 * between the crystals in the Lattice design. Right angles are the whole point —
 * a curve would read as a wire, not a printed trace.
 */
internal fun DrawScope.circuitTrace(
    from: Offset,
    to: Offset,
    color: Color,
    strips: Int = 3,
    spacing: Float = 3f,
    alpha: Float = 0.9f,
) {
    for (s in 0 until strips) {
        val off = (s - (strips - 1) / 2f) * px(spacing)
        val path = Path().apply {
            moveTo(from.x, from.y + off)
            lineTo((from.x + to.x) / 2f, from.y + off)
            lineTo((from.x + to.x) / 2f, to.y + off)
            lineTo(to.x, to.y + off)
        }
        drawPath(
            path = path,
            color = color.copy(alpha = if (s == strips / 2) alpha else alpha * 0.45f),
            style = Stroke(px(if (s == strips / 2) 1.8f else 1f)),
        )
    }
}

/**
 * A crystal facet: a filled translucent triangle showing its internal
 * triangulation, which is what makes the shapes in the designs read as cut glass
 * rather than as flat polygons.
 */
internal fun DrawScope.crystalFacet(
    a: Offset,
    b: Offset,
    c: Offset,
    fill: Color,
    edge: Color,
    fillAlpha: Float = 0.55f,
) {
    val path = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        close()
    }
    drawPath(path, fill.copy(alpha = fillAlpha))
    // Internal lines from each corner to the opposite midpoint — the cut lines.
    val midAB = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val midBC = Offset((b.x + c.x) / 2f, (b.y + c.y) / 2f)
    val midCA = Offset((c.x + a.x) / 2f, (c.y + a.y) / 2f)
    drawLine(edge.copy(alpha = 0.30f), c, midAB, px(0.8f))
    drawLine(edge.copy(alpha = 0.30f), a, midBC, px(0.8f))
    drawLine(edge.copy(alpha = 0.30f), b, midCA, px(0.8f))
    drawPath(path, edge.copy(alpha = 0.95f), style = Stroke(px(1.1f)))
}

/**
 * A tapered energy ribbon: a broad soft band with a bright core along it, for
 * the swirling arcs in the Reactor design. Drawn as three concentric arcs of
 * decreasing width and increasing brightness, which gives the glow falloff a
 * single stroke cannot.
 */
internal fun DrawScope.energyRibbon(
    radius: Float,
    startAngle: Float,
    sweep: Float,
    color: Color,
    width: Float,
    alpha: Float = 1f,
) {
    val layers = listOf(
        Triple(width * 3.2f, 0.10f, 0f),
        Triple(width * 1.7f, 0.28f, 0f),
        Triple(width * 0.7f, 0.85f, 0f),
    )
    for ((w, a, _) in layers) {
        drawArc(
            color = color.copy(alpha = a * alpha),
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(px(w), cap = StrokeCap.Round),
        )
    }
}

/**
 * A perspective ground mesh: horizontal lines bunching toward a horizon and
 * verticals fanning out from it. The lower third of four of the designs.
 */
internal fun DrawScope.groundMesh(
    horizonY: Float,
    color: Color,
    rows: Int = 9,
    columns: Int = 14,
    alpha: Float = 0.22f,
) {
    val w = size.width
    val h = size.height
    if (horizonY >= h) return

    // Rows: spacing grows with distance from the horizon, which is the whole
    // illusion — evenly spaced lines read as a flat ladder.
    for (i in 1..rows) {
        val t = i.toFloat() / rows
        val y = horizonY + (h - horizonY) * t * t
        drawLine(
            color = color.copy(alpha = alpha * (0.35f + t * 0.65f)),
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = px(1f),
        )
    }
    // Columns: fanning from a vanishing point above the horizon.
    val vanish = Offset(w / 2f, horizonY)
    for (i in 0..columns) {
        val t = i.toFloat() / columns
        val x = (t - 0.5f) * w * 3.2f + w / 2f
        drawLine(
            color = color.copy(alpha = alpha * 0.7f),
            start = vanish,
            end = Offset(x, h),
            strokeWidth = px(1f),
        )
    }
}
