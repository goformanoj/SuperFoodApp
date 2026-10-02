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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.jarvis.os.ui.theme.JarvisPalette
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The Reactor Hall (Arc's own world): the inside of a machine, with the reactor at its heart.
 * Conduits run out from the centre to the walls with pulses of light racing along them, hexagonal
 * armour plates turn slowly around the core, and circuit traces creep in from either edge with a
 * data packet riding each one. Cyan for the energy, gold for the occasional surge.
 */
@Composable
fun ReactorWorld(palette: JarvisPalette, modifier: Modifier = Modifier, live: Boolean = true, thumbnail: Boolean = false) {
    val clock = rememberWorldTime(live, thumbnail)
    val traces = remember { circuitTraces(18) }
    val sparks = remember { Rng(77).let { r -> List(70) { floatArrayOf(r.next() * 360f, r.next(), r.range(0.04f, 0.12f), r.range(0.8f, 2.2f)) } } }

    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val w = size.width
        val h = size.height
        val u = designUnit()
        val hair = (1.1f * u).coerceAtLeast(1f)
        val accent = palette.accent
        val warm = palette.highlight
        val c = Offset(w / 2f, if (thumbnail) h * 0.5f else h * 0.43f)
        val base = min(w, h) * 0.5f
        val reach = hypot(max(c.x, w - c.x), max(c.y, h - c.y))

        drawRect(Brush.verticalGradient(0f to palette.background, 0.6f to palette.surface.copy(alpha = 0.6f).compositeOver(palette.background), 1f to palette.background))
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), palette.secondary.copy(alpha = 0.09f), Color.Transparent), c, base * 1.5f), radius = base * 1.5f, center = c)

        // Armour plates: three nested hexagons turning against each other, a node on every corner.
        for ((i, f) in listOf(0.46f, 0.74f, 1.08f).withIndex()) {
            val r = base * f
            val deg = t * (if (i % 2 == 0) 1.6f else -1.1f) + i * 11f
            val path = hexagon(c, r, deg)
            drawPath(path, accent.copy(alpha = 0.10f + 0.04f * i), style = Stroke(hair * (1f + i * 0.5f)))
            for (k in 0 until 6) {
                val p = polar(c, deg + k * 60f + 30f, r)
                drawRect(accent.copy(alpha = 0.55f), Offset(p.x - 2.2f * u, p.y - 2.2f * u), Size(4.4f * u, 4.4f * u))
            }
        }

        // Conduits: 36 spokes; every third carries a pulse that races from the core to the wall.
        val r0 = base * 0.26f
        for (k in 0 until 36) {
            val deg = k * 10f + 5f
            val active = k % 3 == 0
            drawLine(accent.copy(alpha = if (active) 0.17f else 0.06f), polar(c, deg, r0), polar(c, deg, reach), hair)
            if (active) {
                val speed = 0.11f + 0.05f * ((k / 3) % 4)
                val p = (t * speed + k * 0.137f) % 1f
                val a = r0 + (reach - r0) * p
                val b = a + (reach - r0) * 0.12f
                val gold = k % 6 == 0
                val col = if (gold) warm else accent
                drawLine(Brush.linearGradient(listOf(Color.Transparent, col.copy(alpha = 0.95f)), polar(c, deg, a), polar(c, deg, b)), polar(c, deg, a), polar(c, deg, b), hair * 2.4f, StrokeCap.Round, blendMode = BlendMode.Plus)
                glowDot(Color.White, polar(c, deg, b), 1.6f * u + 0.6f, 0.9f)
            }
        }

        // Circuit traces from the edges, each with a data packet travelling toward the hall.
        for ((i, tr) in traces.withIndex()) {
            val pts = tr.points.map { Offset(it[0] * w, it[1] * h) }
            val lens = pts.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
            val total = lens.sum()
            for (s in 0 until pts.size - 1) drawLine(accent.copy(alpha = 0.12f), pts[s], pts[s + 1], hair)
            drawCircle(accent.copy(alpha = 0.45f), 3.4f * u + 1f, pts.last(), style = Stroke(hair))
            var d = ((t * tr.speed + tr.phase) % 1f) * total
            var s = 0
            while (s < lens.size - 1 && d > lens[s]) { d -= lens[s]; s++ }
            val f = if (lens[s] > 0f) d / lens[s] else 0f
            val pos = Offset(pts[s].x + (pts[s + 1].x - pts[s].x) * f, pts[s].y + (pts[s + 1].y - pts[s].y) * f)
            glowDot(if (i % 5 == 0) warm else accent, pos, 2f * u + 0.8f, 0.95f)
        }

        // Sparks thrown outward from the core, fading as they go.
        for (sp in sparks) {
            val p = (t * sp[2] + sp[1]) % 1f
            val pos = polar(c, sp[0] + p * 14f, r0 * 0.7f + p * reach * 0.8f)
            glowDot(if (sp[3] > 1.8f) warm else Color.White, pos, (0.9f + sp[3] * 0.4f) * u, (1f - p) * 0.7f)
        }

        cornerBrackets(accent, u, 0.5f)
        vignette(palette.background)
    }
}

private class Trace(val points: List<FloatArray>, val speed: Float, val phase: Float)

/** Edge-to-centre routes made of straight runs and 45° bends, in unit coordinates (0..1 of the canvas). */
private fun circuitTraces(n: Int): List<Trace> {
    val rng = Rng(4242)
    return List(n) { i ->
        val left = i % 2 == 0
        val x0 = if (left) 0f else 1f
        val dir = if (left) 1f else -1f
        var x = x0
        var y = rng.range(0.08f, 0.92f)
        val pts = mutableListOf(floatArrayOf(x, y))
        val towardsY = if (y < 0.5f) 1f else -1f
        repeat(3) { step ->
            x += dir * rng.range(0.04f, 0.09f); pts += floatArrayOf(x, y)
            val dy = towardsY * rng.range(0.03f, 0.07f)
            x += dir * abs(dy); y += dy; pts += floatArrayOf(x, y)
            if (step == 1) { x += dir * rng.range(0.02f, 0.05f); pts += floatArrayOf(x, y) }
        }
        Trace(pts, rng.range(0.05f, 0.11f), rng.next())
    }
}

private fun abs(v: Float) = if (v < 0f) -v else v

private fun hexagon(c: Offset, r: Float, rotation: Float): Path = Path().apply {
    for (i in 0 until 6) {
        val p = polar(c, rotation + i * 60f + 30f, r)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}
