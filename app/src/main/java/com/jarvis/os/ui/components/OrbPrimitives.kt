package com.jarvis.os.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * What is left of the hand-drawn vocabulary, now that the orbs are the shipped
 * artwork with their own rings rotating.
 *
 * Everything that used to reconstruct an orb, and everything that used to orbit
 * around one, has been deleted across three passes: the first two because
 * vector shapes cannot reach a photorealistic render, the third because rings
 * added outside the artwork read as decoration bolted onto a picture. Only the
 * backdrop still draws: stars with flares, and the ground mesh.
 */

/** Stroke widths in dp — a raw pixel width is hairline on a dense screen. */
internal fun DrawScope.px(dp: Float): Float = dp * density





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
