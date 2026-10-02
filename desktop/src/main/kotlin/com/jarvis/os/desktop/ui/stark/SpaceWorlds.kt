package com.jarvis.os.desktop.ui.stark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import com.jarvis.os.ui.theme.JarvisPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val TAU = (2.0 * PI).toFloat()

/** Stars in three depth layers: far ones small, dim and slow; near ones larger, brighter, drifting faster. */
private fun DrawScope.drawStarLayers(stars: List<FloatArray>, t: Float, areaH: Float) {
    for (s in stars) {
        val layer = s[2]
        val x = ((s[0] + t * 0.0016f * (0.4f + layer)) % 1f) * size.width
        val tw = 0.55f + 0.45f * sin(t * s[3] + s[4] * TAU)
        drawCircle(Color.White.copy(alpha = (0.10f + 0.45f * layer) * tw), 0.6f + 1.5f * layer, Offset(x, s[1] * areaH))
    }
}

private fun starField(n: Int, seed: Int): List<FloatArray> = Rng(seed).let { r -> List(n) { floatArrayOf(r.next(), r.next(), r.next().pow(2f), r.range(0.4f, 2f), r.next()) } }

/**
 * Deep Space (Nebula's own world): a spiral galaxy turning slowly behind the orb, framed by banks
 * of drifting violet and copper gas, three layers of stars sliding past at different speeds, a few
 * constellation lines, and an occasional shooting star. Slow on purpose: nothing here hurries.
 */
@Composable
fun DeepSpaceWorld(palette: JarvisPalette, modifier: Modifier = Modifier, live: Boolean = true, thumbnail: Boolean = false) {
    val clock = rememberWorldTime(live, thumbnail)
    val stars = remember { starField(190, 5) }
    // radius fraction, base angle, jitter, which arm
    val dust = remember { Rng(9).let { r -> List(820) { val arm = it % 2; floatArrayOf(r.next().pow(0.75f), arm * PI.toFloat() + r.range(-0.22f, 0.22f), r.range(-1f, 1f), r.next()) } } }
    val constellation = remember { listOf(floatArrayOf(0.12f, 0.2f), floatArrayOf(0.2f, 0.12f), floatArrayOf(0.29f, 0.17f), floatArrayOf(0.35f, 0.08f), floatArrayOf(0.27f, 0.27f)) }

    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val w = size.width
        val h = size.height
        val u = designUnit()
        val hair = (1.1f * u).coerceAtLeast(1f)
        val c = Offset(w / 2f, if (thumbnail) h * 0.5f else h * 0.43f)
        val R = min(w, h) * 0.62f

        drawRect(Brush.verticalGradient(0f to palette.background, 0.6f to palette.surface.copy(alpha = 0.8f), 1f to palette.background))

        // Gas: six big soft clouds drifting on slow loops, added together so overlaps glow.
        val tints = listOf(palette.accent, palette.secondary, palette.highlight, palette.accent, palette.secondary, palette.highlight)
        for (i in 0 until 6) {
            val cx = w * (0.5f + 0.38f * cos(t * 0.021f + i * 1.1f))
            val cy = h * (0.46f + 0.30f * sin(t * 0.017f + i * 2.3f))
            val r = max(w, h) * (0.30f + 0.06f * (i % 3))
            drawCircle(Brush.radialGradient(listOf(tints[i].copy(alpha = 0.17f), Color.Transparent), Offset(cx, cy), r), radius = r, center = Offset(cx, cy), blendMode = BlendMode.Plus)
        }

        drawStarLayers(stars, t, h)

        // The galaxy: two log-spiral arms of dust, tipped away from us (squashed vertically), turning once in ~8 minutes.
        val spin = t * 0.013f
        val core = ArrayList<Offset>(); val mid = ArrayList<Offset>(); val rim = ArrayList<Offset>()
        for (d in dust) {
            val r = d[0] * R
            val th = d[1] + d[0] * 4.4f + spin * TAU
            val spread = d[2] * (0.05f + 0.10f * d[0]) * R
            val px = c.x + cos(th) * r - sin(th) * spread
            val py = c.y + (sin(th) * r + cos(th) * spread) * 0.42f
            val p = Offset(px, py)
            when {
                d[0] < 0.28f -> core += p
                d[0] < 0.62f -> mid += p
                else -> rim += p
            }
        }
        drawCircle(Brush.radialGradient(listOf(palette.highlight.copy(alpha = 0.40f), palette.accent.copy(alpha = 0.10f), Color.Transparent), c, R * 0.34f), radius = R * 0.34f, center = c, blendMode = BlendMode.Plus)
        drawPoints(rim, PointMode.Points, palette.accent.copy(alpha = 0.55f), 1.7f * u + 0.5f, StrokeCap.Round, blendMode = BlendMode.Plus)
        drawPoints(mid, PointMode.Points, lerp(palette.accent, palette.highlight, 0.5f).copy(alpha = 0.7f), 2f * u + 0.6f, StrokeCap.Round, blendMode = BlendMode.Plus)
        drawPoints(core, PointMode.Points, palette.highlight.copy(alpha = 0.85f), 2.3f * u + 0.7f, StrokeCap.Round, blendMode = BlendMode.Plus)

        // A constellation, faintly joined, in the upper left.
        val pts = constellation.map { Offset(it[0] * w, it[1] * h) }
        for (i in 0 until pts.size - 1) drawLine(palette.highlight.copy(alpha = 0.16f), pts[i], pts[i + 1], hair)
        for (p in pts) drawCircle(Color.White.copy(alpha = 0.8f), 1.8f * u + 0.6f, p)

        // A shooting star every ~11 seconds.
        val cycle = (t / 11f) % 1f
        if (cycle < 0.09f) {
            val k = cycle / 0.09f
            val start = Offset(w * 0.78f, h * 0.10f)
            val head = Offset(start.x - k * w * 0.32f, start.y + k * h * 0.16f)
            val tail = Offset(head.x + w * 0.07f, head.y - h * 0.03f)
            drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f * (1f - k * 0.4f))), tail, head), tail, head, 1.8f * u + 0.6f, StrokeCap.Round)
        }

        cornerBrackets(palette.accent, u, 0.35f)
        vignette(palette.background, 0.86f)
    }
}

/**
 * Orbital (Orbit's own world): a planet seen from above its atmosphere. The lit limb curves across
 * the bottom with a glowing rim and city lights on the dark side, three orbital tracks tilt around
 * the orb with satellites running along them, and a station — solar wings, a blinking beacon —
 * drifts through the upper corner.
 */
@Composable
fun OrbitalWorld(palette: JarvisPalette, modifier: Modifier = Modifier, live: Boolean = true, thumbnail: Boolean = false) {
    val clock = rememberWorldTime(live, thumbnail)
    val stars = remember { starField(150, 12) }
    val cities = remember { Rng(21).let { r -> List(130) { floatArrayOf(r.range(222f, 318f), r.range(0.80f, 0.975f), r.next(), r.range(0.4f, 1f)) } } }

    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val w = size.width
        val h = size.height
        val u = designUnit()
        val hair = (1.1f * u).coerceAtLeast(1f)
        val c = Offset(w / 2f, if (thumbnail) h * 0.45f else h * 0.40f)
        val base = min(w, h) * 0.5f

        drawRect(Brush.verticalGradient(0f to palette.background, 1f to palette.surface.copy(alpha = 0.5f)))
        drawStarLayers(stars, t, h * 0.72f)

        // The planet: a huge disc whose centre is far below the frame, so only its limb shows.
        val pc = Offset(w / 2f, h * 1.62f)
        val pr = h * 1.0f
        drawCircle(Brush.radialGradient(0f to Color(0xFF16406E), 0.5f to Color(0xFF0A2142), 1f to palette.background, center = Offset(pc.x, pc.y - pr * 0.58f), radius = pr * 1.18f), radius = pr, center = pc)
        // Atmosphere: the rim, drawn as stacked strokes so it blooms rather than being a hard line.
        val rim = Brush.linearGradient(listOf(palette.secondary, palette.accent, palette.secondary), Offset(0f, pc.y - pr), Offset(w, pc.y - pr))
        drawCircle(rim, pr + 26f * u, pc, alpha = 0.07f, style = Stroke(52f * u))
        drawCircle(rim, pr + 8f * u, pc, alpha = 0.15f, style = Stroke(18f * u))
        drawCircle(rim, pr, pc, alpha = 0.9f, style = Stroke(2.4f * u + 0.6f), blendMode = BlendMode.Plus)
        // City lights scattered along the dark side of the limb, twinkling.
        for (cl in cities) {
            val p = polar(pc, cl[0], pr * cl[1])
            if (p.y < h && p.y > h * 0.55f) {
                val tw = 0.45f + 0.55f * sin(t * (0.6f + cl[3]) + cl[2] * TAU)
                glowDot(palette.highlight, p, 0.9f * u + 0.3f, 0.5f * tw * cl[3])
            }
        }

        // Orbital tracks around the orb, tilted differently, with a satellite on each.
        for ((i, f) in listOf(0.95f, 1.30f, 1.70f).withIndex()) {
            val rx = base * f
            val ry = rx * 0.27f
            val tilt = listOf(-16f, 7f, -4f)[i]
            val col = if (i == 1) palette.highlight else palette.accent
            rotate(tilt, c) {
                // Dashed track.
                for (k in 0 until 72) if (k % 3 != 2) {
                    drawArc(col.copy(alpha = 0.22f), k * 5f, 3f, false, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), style = Stroke(hair))
                }
                val ang = (t * (0.11f - 0.025f * i) + i * 0.33f) % 1f * TAU
                for (tr in 0 until 6) {
                    val a = ang - tr * 0.045f
                    glowDot(col, Offset(c.x + cos(a) * rx, c.y + sin(a) * ry), (2.4f - tr * 0.3f) * u + 0.3f, (1f - tr * 0.16f))
                }
            }
        }

        // The station, drifting slowly across the upper right: truss, modules, two solar wings, a beacon.
        val sx = w * 0.82f + sin(t * 0.05f) * 26f * u
        val sy = h * 0.19f + cos(t * 0.04f) * 8f * u
        val steel = palette.accent.copy(alpha = 0.55f)
        drawLine(steel, Offset(sx - 62f * u, sy), Offset(sx + 62f * u, sy), hair * 1.6f)
        drawRect(steel, Offset(sx - 10f * u, sy - 7f * u), Size(20f * u, 14f * u), style = Stroke(hair))
        for (side in listOf(-1f, 1f)) {
            val x0 = sx + side * 24f * u
            drawRect(palette.secondary.copy(alpha = 0.30f), Offset(x0 - 17f * u, sy - 30f * u), Size(34f * u, 22f * u))
            drawRect(steel, Offset(x0 - 17f * u, sy - 30f * u), Size(34f * u, 22f * u), style = Stroke(hair))
            drawLine(steel.copy(alpha = 0.4f), Offset(x0, sy - 30f * u), Offset(x0, sy - 8f * u), hair)
        }
        if (floor(t * 1.2f).toInt() % 2 == 0) glowDot(palette.highlight, Offset(sx + 62f * u, sy), 2.4f * u + 0.6f)

        cornerBrackets(palette.accent, u, 0.4f)
        vignette(palette.background, 0.80f, 0.4f)
    }
}
