package com.jarvis.os.desktop.ui.stark

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.jarvis.os.ui.theme.JarvisPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Skyline (the Holo theme's world): a city at night under a heavy sky, with its own lights mirrored in a river.
 * The towers and their windows are painted ONCE into a cached bitmap per window size — thousands of lit windows
 * would be far too much to redraw sixty times a second — and the frame loop animates only what is cheap and
 * calm: slow cloud banks, a few windows twinkling, the red beacons on the two tall towers, and ripples on the water.
 */
@Composable
fun SkylineWorld(palette: JarvisPalette, modifier: Modifier = Modifier, live: Boolean = true, thumbnail: Boolean = false) {
    val clock = rememberWorldTime(live, thumbnail)
    val towers = remember { cityLayers() }

    Box(
        modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val horizon = h * 0.74f
            val u = (min(w, h) / 900f).coerceIn(0.3f, 1.3f)
            val city = if (w > 1f && h > 1f) renderCity(this, w.toInt(), h.toInt(), horizon, u, palette, towers) else null
            val twinkles = towers.last().flatMap { b -> b.twinkles }.take(70)

            onDrawBehind {
                val t = clock.floatValue
                val accent = palette.accent
                val warm = palette.highlight
                drawRect(palette.background)
                if (city != null) drawImage(city)

                // Cloud banks, drifting right across the sky and wrapping.
                for (i in 0 until 5) {
                    val cx = (((i * 0.31f + t * 0.0045f * (0.6f + 0.2f * i)) % 1.3f) - 0.15f) * w
                    val cy = h * (0.12f + 0.06f * i)
                    scale(1f, 0.28f, Offset(cx, cy)) {
                        drawCircle(Brush.radialGradient(listOf(palette.secondary.copy(alpha = 0.14f), Color.Transparent), Offset(cx, cy), w * 0.30f), w * 0.30f, Offset(cx, cy))
                    }
                }

                // Windows that flicker on and off, a handful at a time.
                for (tw in twinkles) {
                    val on = sin(t * 0.55f + tw.phase * 6.28f) > 0.86f
                    if (on) drawRect((if (tw.warm) warm else accent).copy(alpha = 0.9f), Offset(tw.x * w, tw.y * horizon), Size(2.6f * u + 0.6f, 3.8f * u + 0.8f))
                }
                // Aircraft beacons on the tall towers.
                if ((t % 2f) < 0.9f) for (b in towers.last().filter { it.hero }) glowDot(Color(0xFFFF4D4D), Offset((b.x + b.w / 2f) * w, horizon - b.h * horizon - 5f * u), 2f * u + 0.8f)

                // The river: the city flipped about the horizon and faded, then a body of water over it.
                clipRect(0f, horizon, w, h) {
                    if (city != null) scale(1f, -1f, Offset(0f, horizon)) { drawImage(city, alpha = 0.34f) }
                    drawRect(Brush.verticalGradient(listOf(palette.background.copy(alpha = 0.10f), palette.background.copy(alpha = 0.92f)), horizon, h), Offset(0f, horizon), Size(w, h - horizon))
                    // Ripples: long thin highlights sliding sideways at different speeds, thicker toward the viewer.
                    for (i in 0 until 16) {
                        val f = (i + 1) / 17f
                        val y = horizon + (h - horizon) * f * f
                        val len = w * (0.05f + 0.10f * f)
                        val x = (((i * 0.2371f + t * 0.006f * (0.5f + f)) % 1.2f) - 0.1f) * w
                        drawLine((if (i % 3 == 0) warm else accent).copy(alpha = 0.10f + 0.12f * f), Offset(x, y), Offset(x + len, y), 1f + 1.6f * f * u)
                    }
                }
                drawLine(accent.copy(alpha = 0.45f), Offset(0f, horizon), Offset(w, horizon), 1.4f)
                drawRect(Brush.radialGradient(0.50f to Color.Transparent, 1f to palette.background.copy(alpha = 0.78f), center = Offset(w / 2, h * 0.5f), radius = max(w, h) * 0.8f))
            }
        },
    )
}

// ── the static city ─────────────────────────────────────────────────────────────

private class Twinkle(val x: Float, val y: Float, val phase: Float, val warm: Boolean)

/** One tower, in unit coordinates: x/w across the canvas, h as a fraction of the sky height (0 at the horizon). */
private class Building(val x: Float, val w: Float, val h: Float, val kind: Int, val seed: Int, val hero: Boolean, val twinkles: List<Twinkle> = emptyList())

private fun cityLayers(): List<List<Building>> {
    val rng = Rng(2024)
    fun layer(count: Int, minH: Float, maxH: Float, minW: Float, maxW: Float, heroes: Int): List<Building> {
        val out = ArrayList<Building>()
        var x = -0.02f
        for (i in 0 until count) {
            val w = rng.range(minW, maxW)
            val hero = heroes > 0 && (i == count / 3 || i == (count * 2) / 3) && heroes >= 1
            val h = if (hero) maxH + 0.06f else rng.range(minH, maxH)
            out += Building(x, w, h, if (hero) 2 else (rng.next() * 2f).toInt(), (rng.next() * 1e6f).toInt(), hero)
            x += w * rng.range(0.55f, 1.05f)
            if (x > 1.02f) break
        }
        return out
    }
    val far = layer(34, 0.10f, 0.26f, 0.025f, 0.055f, 0)
    val mid = layer(22, 0.16f, 0.40f, 0.035f, 0.075f, 0)
    val nearBase = layer(14, 0.26f, 0.52f, 0.05f, 0.10f, 1)
    val centres = listOf(0.41f, 0.58f)
    val heroIdx = centres.map { c -> nearBase.indices.minByOrNull { kotlin.math.abs(nearBase[it].x + nearBase[it].w / 2f - c) }!! }
    val near = nearBase.mapIndexed { i, b0 ->
        val hi = heroIdx.indexOf(i)
        val b = if (hi >= 0) Building(b0.x, 0.062f, if (hi == 0) 0.80f else 0.90f, 2, b0.seed, true) else Building(b0.x, b0.w, b0.h.coerceAtMost(0.46f), b0.kind, b0.seed, false)
        val tw = ArrayList<Twinkle>()
        if (b.w > 0.04f) repeat(6) { tw += Twinkle(b.x + rng.range(0.1f, 0.9f) * b.w, 1f - rng.range(0.05f, b.h) , rng.next(), rng.next() < 0.3f) }
        Building(b.x, b.w, b.h, b.kind, b.seed, b.hero, tw)
    }
    return listOf(far, mid, near)
}

private fun renderCity(density: Density, w: Int, h: Int, horizon: Float, u: Float, p: JarvisPalette, layers: List<List<Building>>): ImageBitmap {
    val bmp = ImageBitmap(w, h)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
        val fw = w.toFloat()
        // Sky: near-black blue overhead, a bruised violet-rose band at the horizon like the reference.
        val glow = lerp(lerp(p.highlight, p.secondary, 0.5f), Color(0xFFB05A9A), 0.45f)
        drawRect(Brush.verticalGradient(0f to p.background, 0.38f to lerp(p.background, p.secondary, 0.34f), 0.78f to lerp(p.background, glow, 0.62f), 1f to lerp(p.background, glow, 0.92f), startY = 0f, endY = horizon), Offset.Zero, Size(fw, horizon))
        // A pale planet low in the sky with a ring.
        val pc = Offset(fw * 0.88f, horizon * 0.30f)
        val pr = horizon * 0.075f
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), p.secondary.copy(alpha = 0.30f), Color.Transparent), pc, pr * 3f), pr * 3f, pc)
        drawCircle(lerp(p.background, Color.White, 0.55f).copy(alpha = 0.85f), pr, pc)
        scale(1f, 0.22f, pc) { drawCircle(Color.White.copy(alpha = 0.35f), pr * 1.9f, pc, style = Stroke(1.6f * u)) }

        val tints = listOf(0.50f, 0.24f, 0.05f)
        for ((li, layer) in layers.withIndex()) {
            val base = lerp(p.background, glow, tints[li])
            for (b in layer) drawTower(b, base, li, fw, horizon, u, p)
            // Mist between the layers, so distance reads as haze.
            if (li < 2) drawRect(Brush.verticalGradient(listOf(Color.Transparent, lerp(p.background, glow, 0.30f).copy(alpha = 0.34f)), horizon * 0.55f, horizon), Offset(0f, horizon * 0.55f), Size(fw, horizon * 0.45f))
        }
    }
    return bmp
}

private fun DrawScope.drawTower(b: Building, base: Color, layer: Int, fw: Float, horizon: Float, u: Float, p: JarvisPalette) {
    val x = b.x * fw
    val bw = b.w * fw
    val top = horizon - b.h * horizon
    val body = Path().apply {
        when {
            b.hero -> { // a pointed tower: straight shaft, tapering crown
                moveTo(x, horizon); lineTo(x, top + bw * 0.9f); lineTo(x + bw * 0.5f, top); lineTo(x + bw, top + bw * 0.9f); lineTo(x + bw, horizon)
            }
            b.kind == 1 -> { // stepped: a narrower block on top
                moveTo(x, horizon); lineTo(x, top + b.h * horizon * 0.18f); lineTo(x + bw * 0.2f, top + b.h * horizon * 0.18f)
                lineTo(x + bw * 0.2f, top); lineTo(x + bw * 0.8f, top); lineTo(x + bw * 0.8f, top + b.h * horizon * 0.18f); lineTo(x + bw, top + b.h * horizon * 0.18f); lineTo(x + bw, horizon)
            }
            else -> { moveTo(x, horizon); lineTo(x, top); lineTo(x + bw, top); lineTo(x + bw, horizon) }
        }
        close()
    }
    drawPath(body, base)
    if (b.hero) { // the antenna on a tall tower
        drawLine(base, Offset(x + bw / 2f, top), Offset(x + bw / 2f, top - horizon * 0.05f), 1.6f * u + 0.4f)
    }
    // Lit windows: a grid, each lit by a hash; cool light mostly, warm now and then.
    val cellW = (7f - layer * 1.5f) * u + 2f
    val cellH = (10f - layer * 2f) * u + 3f
    val density = listOf(0.07f, 0.14f, 0.26f)[layer]
    val ww = cellW * 0.34f
    val wh = cellH * 0.42f
    var cy = top + cellH * 1.2f
    var row = 0
    while (cy < horizon - cellH * 0.5f) {
        var cx = x + cellW * 0.6f
        var col = 0
        while (cx < x + bw - cellW * 0.6f) {
            val hsh = StarkGeometry.hash01(b.seed, row, col)
            if (hsh < density) {
                val warm = StarkGeometry.hash01(row, col, b.seed) < 0.26f
                drawRect((if (warm) p.highlight else p.accent).copy(alpha = 0.30f + 0.55f * hsh / density), Offset(cx, cy), Size(ww, wh))
            }
            cx += cellW; col++
        }
        cy += cellH; row++
    }
    // A thin rim of light down the lit edge of the near towers, as in the reference.
    if (layer == 2) drawLine(p.accent.copy(alpha = 0.32f), Offset(x + bw, max(top, horizon - b.h * horizon)), Offset(x + bw, horizon), 1.2f * u)
}
