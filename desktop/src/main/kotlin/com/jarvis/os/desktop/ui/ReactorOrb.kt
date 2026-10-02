package com.jarvis.os.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.voice.OrbState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The desktop reactor: a HUD core built from concentric instruments — dial scales,
 * segmented dashes, broken arcs and a gear ring, each turning on its own clock and
 * direction — around a white-hot core inside a drifting particle shell, with a radar
 * sweep passing over it all. Modelled on the references the user chose: cyan light
 * inside, warm arcs outside (the theme's accent and its highlight — so Arc reads
 * cyan/orange and Forge reads gold).
 *
 * Its motion is the app's REAL state: idle turns slowly; thinking spins every ring
 * up and brightens the core; an error bleeds the warm arcs red; offline dims it.
 *
 * One clock drives it, read only inside the draw — each frame invalidates this
 * Canvas and nothing else in the tree.
 *
 * [animated] = false draws it once at a fixed moment and runs no clock — what the theme picker uses.
 */
@Composable
fun ReactorOrb(size: Dp, state: OrbState, modifier: Modifier = Modifier, labels: Boolean = true, animated: Boolean = true) {
    val palette = LocalPalette.current
    // A still orb (theme-picker previews) is drawn once at a fixed, good-looking moment and runs no clock at all.
    var t by remember { mutableFloatStateOf(if (animated) 0f else 4f) }
    var speed by remember { mutableFloatStateOf(1f) }
    val target = when (state) {
        OrbState.Thinking -> 3.2f
        OrbState.Listening, OrbState.Speaking -> 1.8f
        OrbState.Offline -> 0.15f
        else -> 1f
    }
    val liveTarget by rememberUpdatedState(target)
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                    // Ease the spin-up/down so a state change accelerates rather than jumps.
                    speed += (liveTarget - speed) * (dt * 2.5f).coerceAtMost(1f)
                    t += dt * speed
                }
                last = now
            }
        }
    }

    val offline = state == OrbState.Offline
    val cool = if (offline) lerp(palette.accent, Color(0xFF7D8A99), 0.7f) else palette.accent
    val warm = when {
        state == OrbState.Error -> Color(0xFFFF4D4D)
        offline -> lerp(palette.highlight, Color(0xFF7D8A99), 0.7f)
        else -> palette.highlight
    }
    val coreGain = when (state) {
        OrbState.Thinking -> 1f
        OrbState.Offline -> 0.35f
        else -> 0.8f
    }
    val points = remember { fibonacciSphere(170) }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(cool) { TextStyle(color = cool.copy(alpha = 0.55f), fontSize = 8.sp, fontFamily = J.Mono) }
    // Measured once, not every frame: text layout is the most expensive thing on the dial.
    val dialLabels = remember(labelStyle) { List(12) { measurer.measure(String.format("%03d", it * 30), labelStyle) } }

    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f * 0.84f   // leave room for glow + labels
        val time = t
        val thin = (r / 170f).coerceAtLeast(0.8f)

        // ── Halo ─────────────────────────────────────────────────────────────
        drawCircle(
            Brush.radialGradient(listOf(cool.copy(alpha = 0.22f * coreGain), cool.copy(alpha = 0.06f), Color.Transparent), c, r * 1.18f),
            radius = r * 1.18f, center = c,
        )

        // ── Radar sweep ───────────────────────────────────────────────────────
        rotate(time * 40f, c) {
            drawArc(
                Brush.sweepGradient(0f to Color.Transparent, 0.85f to Color.Transparent, 1f to cool.copy(alpha = 0.16f), center = c),
                startAngle = 0f, sweepAngle = 360f, useCenter = true,
                topLeft = Offset(c.x - r * 0.97f, c.y - r * 0.97f), size = Size(r * 1.94f, r * 1.94f),
            )
        }

        // ── A: outer dial — 120 ticks, every 10th long, degree labels ─────────
        rotate(time * 5f, c) {
            ring(c, r * 1.0f, cool.copy(alpha = 0.35f), thin)
            for (i in 0 until 120) {
                val a = i * 3f
                val long = i % 10 == 0
                tick(c, a, r * (if (long) 0.955f else 0.975f), r * 1.0f, cool.copy(alpha = if (long) 0.8f else 0.35f), thin * if (long) 1.6f else 1f)
            }
            if (labels) for (i in 0 until 12) {
                val a = i * 30f
                val p = polar(c, a, r * 1.075f)
                val txt = dialLabels[i]
                drawText(txt, topLeft = Offset(p.x - txt.size.width / 2f, p.y - txt.size.height / 2f))
            }
        }

        // ── B: warm broken arcs ────────────────────────────────────────────────
        rotate(-time * 11f, c) {
            glowArc(c, r * 0.915f, 10f, 42f, warm, thin * 3.2f)
            glowArc(c, r * 0.915f, 95f, 70f, warm, thin * 3.2f)
            glowArc(c, r * 0.915f, 210f, 24f, warm, thin * 3.2f)
            glowArc(c, r * 0.915f, 262f, 58f, warm.copy(alpha = 0.6f), thin * 1.6f)
        }

        // ── C: dashed ring ─────────────────────────────────────────────────────
        rotate(time * 22f, c) {
            for (i in 0 until 72) {
                if (i % 9 == 8) continue
                arc(c, r * 0.855f, i * 5f, 2.6f, cool.copy(alpha = 0.75f), thin * 2.4f, StrokeCap.Butt)
            }
        }

        // ── D: long accent arc with a bright node at its head ─────────────────
        rotate(-time * 15f, c) {
            glowArc(c, r * 0.785f, 0f, 225f, cool, thin * 2.2f)
            val head = polar(c, 225f, r * 0.785f)
            drawCircle(Color.White, radius = thin * 3.2f, center = head, blendMode = BlendMode.Plus)
            drawCircle(cool.copy(alpha = 0.35f), radius = thin * 9f, center = head, blendMode = BlendMode.Plus)
            arc(c, r * 0.785f, 240f, 100f, cool.copy(alpha = 0.25f), thin, StrokeCap.Butt)
        }

        // ── E: fine static scale ───────────────────────────────────────────────
        for (i in 0 until 180) {
            tick(c, i * 2f, r * 0.705f, r * (if (i % 15 == 0) 0.74f else 0.725f), cool.copy(alpha = if (i % 15 == 0) 0.6f else 0.22f), thin)
        }

        // ── F: fast opposed warm arcs ──────────────────────────────────────────
        rotate(time * 34f, c) {
            glowArc(c, r * 0.64f, 0f, 62f, warm, thin * 2.4f)
            glowArc(c, r * 0.64f, 180f, 62f, warm, thin * 2.4f)
        }

        // ── G: gear ring — alternating radial blocks ───────────────────────────
        rotate(-time * 26f, c) {
            for (i in 0 until 36) {
                val on = i % 2 == 0
                arc(c, r * 0.555f, i * 10f + 1f, 8f, if (on) cool.copy(alpha = 0.85f) else cool.copy(alpha = 0.18f), r * 0.045f, StrokeCap.Butt)
            }
        }
        ring(c, r * 0.51f, cool.copy(alpha = 0.45f), thin)

        // ── H: inner markers ───────────────────────────────────────────────────
        rotate(time * 48f, c) {
            for (k in 0 until 4) {
                val p = polar(c, k * 90f, r * 0.465f)
                drawCircle(cool, radius = thin * 2.2f, center = p, blendMode = BlendMode.Plus)
            }
            arc(c, r * 0.465f, 20f, 50f, cool.copy(alpha = 0.6f), thin * 1.4f, StrokeCap.Round)
            arc(c, r * 0.465f, 200f, 50f, cool.copy(alpha = 0.6f), thin * 1.4f, StrokeCap.Round)
        }

        // ── Particle shell (3D, drifting) ──────────────────────────────────────
        val spin = time * 0.35f
        val tilt = 0.45f + 0.12f * sin(time * 0.3f)
        val cs = cos(spin); val sn = sin(spin); val ct = cos(tilt); val st = sin(tilt)
        val shellR = r * 0.38f
        for ((i, p) in points.withIndex()) {
            val x1 = p[0] * cs + p[2] * sn
            val z1 = -p[0] * sn + p[2] * cs
            val y2 = p[1] * ct - z1 * st
            val z2 = p[1] * st + z1 * ct
            val depth = (z2 + 1f) / 2f
            val persp = 1f / (1.35f - z2 * 0.35f)
            val pos = Offset(c.x + x1 * shellR * persp, c.y + y2 * shellR * persp)
            val col = if (i % 5 == 0) warm else cool
            drawCircle(col.copy(alpha = 0.12f + 0.75f * depth), radius = thin * (0.7f + 1.5f * depth), center = pos, blendMode = BlendMode.Plus)
        }

        // ── Core ───────────────────────────────────────────────────────────────
        val breathe = 0.88f + 0.12f * sin(time * 2.2f)
        val coreR = r * 0.23f * breathe
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.95f * coreGain),
                0.28f to lerp(Color.White, cool, 0.35f).copy(alpha = 0.85f * coreGain),
                0.6f to cool.copy(alpha = 0.45f * coreGain),
                1f to Color.Transparent,
                center = c, radius = coreR * 1.9f,
            ),
            radius = coreR * 1.9f, center = c, blendMode = BlendMode.Plus,
        )
        ring(c, r * 0.17f, Color.White.copy(alpha = 0.55f * coreGain), thin * 1.2f)
        rotate(-time * 70f, c) {
            arc(c, r * 0.125f, 0f, 90f, Color.White.copy(alpha = 0.7f * coreGain), thin * 1.6f, StrokeCap.Round)
            arc(c, r * 0.125f, 180f, 90f, Color.White.copy(alpha = 0.7f * coreGain), thin * 1.6f, StrokeCap.Round)
        }

        // ── Crosshair ──────────────────────────────────────────────────────────
        val ch = cool.copy(alpha = 0.22f)
        drawLine(ch, Offset(c.x - r * 1.16f, c.y), Offset(c.x - r * 0.8f, c.y), thin)
        drawLine(ch, Offset(c.x + r * 0.8f, c.y), Offset(c.x + r * 1.16f, c.y), thin)
        drawLine(ch, Offset(c.x, c.y - r * 1.16f), Offset(c.x, c.y - r * 0.8f), thin)
        drawLine(ch, Offset(c.x, c.y + r * 0.8f), Offset(c.x, c.y + r * 1.16f), thin)
    }
}

private fun polar(c: Offset, deg: Float, radius: Float): Offset {
    val a = (deg - 90f) * (PI.toFloat() / 180f)
    return Offset(c.x + radius * cos(a), c.y + radius * sin(a))
}

private fun DrawScope.ring(c: Offset, radius: Float, color: Color, width: Float) =
    drawCircle(color, radius = radius, center = c, style = Stroke(width))

private fun DrawScope.arc(c: Offset, radius: Float, start: Float, sweep: Float, color: Color, width: Float, cap: StrokeCap) =
    drawArc(
        color, startAngle = start - 90f, sweepAngle = sweep, useCenter = false,
        topLeft = Offset(c.x - radius, c.y - radius), size = Size(radius * 2, radius * 2),
        style = Stroke(width, cap = cap),
    )

/** An arc with bloom: a wide faint pass added under a sharp bright one. */
private fun DrawScope.glowArc(c: Offset, radius: Float, start: Float, sweep: Float, color: Color, width: Float) {
    val tl = Offset(c.x - radius, c.y - radius)
    val sz = Size(radius * 2, radius * 2)
    drawArc(color.copy(alpha = color.alpha * 0.18f), start - 90f, sweep, false, tl, sz, style = Stroke(width * 4.5f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    drawArc(color.copy(alpha = color.alpha * 0.35f), start - 90f, sweep, false, tl, sz, style = Stroke(width * 2f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    drawArc(color, start - 90f, sweep, false, tl, sz, style = Stroke(width, cap = StrokeCap.Round))
}

private fun DrawScope.tick(c: Offset, deg: Float, r0: Float, r1: Float, color: Color, width: Float) =
    drawLine(color, polar(c, deg, r0), polar(c, deg, r1), width)

/** Evenly spread points on a unit sphere (golden-angle spiral). */
private fun fibonacciSphere(n: Int): List<FloatArray> {
    val golden = (PI * (3.0 - sqrt(5.0))).toFloat()
    return List(n) { i ->
        val y = 1f - (i / (n - 1f)) * 2f
        val rad = sqrt(1f - y * y)
        val th = golden * i
        floatArrayOf(cos(th) * rad, y, sin(th) * rad)
    }
}
