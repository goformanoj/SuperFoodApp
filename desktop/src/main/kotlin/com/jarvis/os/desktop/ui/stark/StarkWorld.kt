package com.jarvis.os.desktop.ui.stark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.jarvis.os.ui.theme.JarvisPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Stark world: a command deck rather than a scene. Nothing here is "a place" — it is the
 * instrument the orb sits inside: a perspective floor drifting toward you, radar range rings
 * with a sweep passing over them, hex cells flickering in the upper corners, live data columns
 * on either edge, a scanner beam crossing the glass, and a bracketed frame with readouts.
 *
 * Built for a laptop: one clock, one Canvas, ~400 cheap primitives a frame, no offscreen
 * layers and no blur — the glow comes from additive blending. When [live] is false (every
 * screen except Home, and every thumbnail) it is drawn once and never redrawn, so it costs
 * nothing behind a scrolling list.
 *
 * Colours come from [palette], so it is cyan-and-amber under Stark and takes on the theme's own
 * colours if the user picks it as a World under another theme.
 */
@Composable
fun StarkWorld(
    palette: JarvisPalette,
    modifier: Modifier = Modifier,
    live: Boolean = true,
    thumbnail: Boolean = false,
) {
    val clock = rememberWorldTime(live, thumbnail)
    val stars = remember { stars(110) }
    val hex = remember { hexagon() }

    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val w = size.width
        val h = size.height
        val u = (min(w, h) / 900f).coerceIn(0.3f, 1.3f)            // one "design pixel", scaled to the canvas
        val hair = (1.1f * u).coerceAtLeast(1f)
        val accent = palette.accent
        val warm = palette.highlight
        val horizon = h * 0.64f
        val orbC = Offset(w * 0.5f, if (thumbnail) h * 0.5f else h * 0.43f)

        drawBase(palette, w, h, horizon, orbC)
        drawStars(stars, w, h, t)
        if (!thumbnail) drawHexCorners(hex, accent, warm, w, h, u, t)
        drawFloor(accent, w, h, horizon, hair, t)
        drawRangeRings(accent, orbC, min(w, h) * 0.5f, hair, t)
        if (!thumbnail) drawDataColumns(accent, warm, w, h, u, t)
        drawScanner(accent, w, h, t)
        drawFrame(accent, warm, w, h, u, hair)
        // The vignette last: it keeps text legible and pulls the eye to the middle.
        drawRect(Brush.radialGradient(0.55f to Color.Transparent, 1f to palette.background.copy(alpha = 0.82f), center = Offset(w / 2, h * 0.46f), radius = maxOf(w, h) * 0.78f))
    }
}

// ── layers ──────────────────────────────────────────────────────────────────

private fun DrawScope.drawBase(p: JarvisPalette, w: Float, h: Float, horizon: Float, orbC: Offset) {
    drawRect(Brush.verticalGradient(0f to p.background, 0.55f to p.surface.copy(alpha = 0.55f).compositeOver(p.background), 1f to p.background))
    // Light pooled behind the orb, and a band of heat along the horizon where the floor meets the dark.
    drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.20f), p.secondary.copy(alpha = 0.07f), Color.Transparent), orbC, min(w, h) * 0.62f), radius = min(w, h) * 0.62f, center = orbC)
    val band = h * 0.16f
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, p.accent.copy(alpha = 0.16f), Color.Transparent), horizon - band, horizon + band), Offset(0f, horizon - band), Size(w, band * 2))
}

private fun DrawScope.drawStars(stars: List<FloatArray>, w: Float, h: Float, t: Float) {
    for (s in stars) {
        val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * s[3] * 0.25f + s[4] * 6.28f))
        drawCircle(Color.White.copy(alpha = 0.10f + 0.34f * tw * s[2]), radius = 0.7f + 1.3f * s[2], center = Offset(s[0] * w, s[1] * h * 0.66f))
    }
}

/** Pointy-top hex cells fading out of the top corners; a few light up at random, as live data would. */
private fun DrawScope.drawHexCorners(hex: Path, accent: Color, warm: Color, w: Float, h: Float, u: Float, t: Float) {
    val s = 26f * u + 8f
    val colW = sqrt(3f) * s
    val rowH = 1.5f * s
    val reach = w * 0.30f
    val tick = floor(t * 0.14f).toInt()
    for (corner in 0..1) {
        val cx = if (corner == 0) 0f else w
        var row = 0
        var y = 0f
        while (y < reach && row < 40) {
            var col = 0
            while (col < 40) {
                val x = if (corner == 0) col * colW + (if (row % 2 == 1) colW / 2 else 0f) else w - col * colW - (if (row % 2 == 1) colW / 2 else 0f)
                val d = sqrt((x - cx) * (x - cx) + y * y)
                if (d > reach) { if (col > 2) break else { col++; continue } }
                val f = StarkGeometry.fade(d, reach)
                if (f > 0.01f) {
                    val lit = StarkGeometry.hash01(row, col + corner * 97, tick) > 0.985f
                    val colr = if (lit) warm else accent
                    // The hex is a unit shape; scale it to the cell, and the stroke inversely so the line stays 1px.
                    translate(x, y) {
                        scale(s, s, Offset.Zero) {
                            if (lit) drawPath(hex, colr.copy(alpha = 0.16f * f + 0.04f))
                            drawPath(hex, colr.copy(alpha = (if (lit) 0.7f else 0.20f) * f), style = Stroke(1f / s))
                        }
                    }
                }
                col++
            }
            row++
            y += rowH
        }
    }
}

private fun DrawScope.drawFloor(accent: Color, w: Float, h: Float, horizon: Float, hair: Float, t: Float) {
    val vanish = Offset(w / 2f, horizon)
    val span = w * 0.115f
    for (k in -11..11) {
        val bottom = Offset(w / 2f + k * span, h)
        val a = if (k % 4 == 0) 0.30f else 0.15f
        drawLine(Brush.linearGradient(listOf(Color.Transparent, accent.copy(alpha = a)), vanish, bottom), vanish, bottom, hair)
    }
    for (f in StarkGeometry.floorRows(t * 0.015f)) {
        val y = horizon + (h - horizon) * f
        drawLine(accent.copy(alpha = 0.04f + 0.30f * f), Offset(0f, y), Offset(w, y), hair)
    }
    drawLine(accent.copy(alpha = 0.55f), Offset(0f, horizon), Offset(w, horizon), hair * 1.2f)
}

private fun DrawScope.drawRangeRings(accent: Color, c: Offset, base: Float, hair: Float, t: Float) {
    for ((i, f) in listOf(0.34f, 0.50f, 0.66f, 0.84f).withIndex()) {
        drawCircle(accent.copy(alpha = 0.07f + 0.02f * i), radius = base * f, center = c, style = Stroke(hair))
    }
    // A graduated bezel on the third ring, turning slowly.
    rotate(t * 0.5f, c) {
        val r = base * 0.66f
        for (i in 0 until 120) {
            val long = i % 10 == 0
            val a = i * 3f
            drawLine(accent.copy(alpha = if (long) 0.34f else 0.13f), polar(c, a, r), polar(c, a, r + if (long) 11f else 5f), hair)
        }
    }
    // Broken outer ring, counter-rotating.
    rotate(-t * 0.8f, c) {
        for (i in 0 until 24) if (i % 5 != 4) drawArc(accent.copy(alpha = 0.22f), i * 15f, 9f, false, Offset(c.x - base * 0.84f, c.y - base * 0.84f), Size(base * 1.68f, base * 1.68f), style = Stroke(hair * 2f, cap = StrokeCap.Butt))
    }
    // The sweep: a wedge of light that fades behind its leading edge.
    rotate(t * 5f, c) {
        drawArc(
            Brush.sweepGradient(0f to Color.Transparent, 0.82f to Color.Transparent, 0.995f to accent.copy(alpha = 0.08f), 1f to accent.copy(alpha = 0.18f), center = c),
            0f, 360f, true, Offset(c.x - base * 0.66f, c.y - base * 0.66f), Size(base * 1.32f, base * 1.32f), blendMode = BlendMode.Plus,
        )
    }
}

private fun DrawScope.drawDataColumns(accent: Color, warm: Color, w: Float, h: Float, u: Float, t: Float) {
    val n = 18
    val gap = 6f * u + 2f
    val bar = 2.2f * u + 0.8f
    val maxH = h * 0.20f
    val base = h * 0.86f
    val edge = 26f * u + 10f
    for (side in 0..1) {
        for (i in 0 until n) {
            val x = if (side == 0) edge + i * gap else w - edge - i * gap
            val f = StarkGeometry.barHeight(i + side * 7, t * 0.12f)
            val bh = maxH * f * (0.55f + 0.45f * (1f - i / n.toFloat()))   // taller toward the screen edge
            drawRect(accent.copy(alpha = 0.18f + 0.22f * f), Offset(x, base - bh), Size(bar, bh))
            drawRect((if (f > 0.9f) warm else accent).copy(alpha = 0.85f), Offset(x, base - bh - bar), Size(bar, bar))
        }
        val x0 = if (side == 0) edge else w - edge - n * gap
        drawLine(accent.copy(alpha = 0.35f), Offset(x0, base + 6f), Offset(x0 + n * gap, base + 6f), 1f)
    }
}

private fun DrawScope.drawScanner(accent: Color, w: Float, h: Float, t: Float) {
    val y = ((t / 40f) % 1f) * (h + 160f) - 80f
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.03f), accent.copy(alpha = 0.06f), Color.Transparent), y - 70f, y + 6f), Offset(0f, y - 70f), Size(w, 76f))
    drawLine(accent.copy(alpha = 0.10f), Offset(0f, y + 6f), Offset(w, y + 6f), 1f)
}

private fun DrawScope.drawFrame(accent: Color, warm: Color, w: Float, h: Float, u: Float, hair: Float) {
    val inset = 14f * u + 6f
    val len = 64f * u + 16f
    val sw = hair * 1.8f
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(accent.copy(alpha = 0.7f), Offset(x, y), Offset(x + dx * len, y), sw, StrokeCap.Square)
        drawLine(accent.copy(alpha = 0.7f), Offset(x, y), Offset(x, y + dy * len), sw, StrokeCap.Square)
        drawLine(accent.copy(alpha = 0.28f), Offset(x + dx * 8f, y + dy * 8f), Offset(x + dx * (len * 0.55f), y + dy * 8f), hair)
    }
    corner(inset, inset, 1f, 1f)
    corner(w - inset, inset, -1f, 1f)
    corner(inset, h - inset, 1f, -1f)
    corner(w - inset, h - inset, -1f, -1f)
    // The notch at top centre: a status lamp between two short rules.
    val cx = w / 2f
    drawLine(accent.copy(alpha = 0.45f), Offset(cx - 90f * u - 12f, inset), Offset(cx - 14f, inset), hair)
    drawLine(accent.copy(alpha = 0.45f), Offset(cx + 14f, inset), Offset(cx + 90f * u + 12f, inset), hair)
    drawCircle(warm, radius = 2.6f * u + 1f, center = Offset(cx, inset), blendMode = BlendMode.Plus)
    drawCircle(warm.copy(alpha = 0.3f), radius = 8f * u + 3f, center = Offset(cx, inset), blendMode = BlendMode.Plus)
}

// ── small helpers ───────────────────────────────────────────────────────────


private fun hexagon(): Path = Path().apply {
    val r = 0.92f
    for (i in 0 until 6) {
        val a = (60f * i - 30f) * (PI.toFloat() / 180f)
        val x = cos(a); val y = sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun stars(n: Int): List<FloatArray> {
    var s = 1234567
    fun next(): Float { s = s * 1103515245 + 12345; return ((s ushr 8) and 0xFFFF) / 65536f }
    return List(n) { floatArrayOf(next(), next(), next(), 0.4f + next() * 1.6f, next()) }
}
