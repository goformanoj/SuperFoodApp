package com.jarvis.os.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * The drawing vocabulary the six themes are built from.
 *
 * Two attempts hand-drew the whole orb from shapes like these and neither
 * resembled the references, which are photorealistic renders. The artwork is now
 * shipped as-is and these survive only for what sits AROUND it: dotted light
 * strips, lens flares and the ground mesh. The shape-building primitives that
 * tried to reconstruct the orbs themselves were deleted rather than left to rot.
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
