package com.jarvis.os.desktop.ui.stark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import com.jarvis.os.ui.theme.JarvisPalette
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * The Foundry (Forge's own world): a furnace hall. A molten grate glows along the floor, embers
 * lift off it and drift up through slow heat haze, shafts of hot light fall from the roof, and
 * steel trusswork frames both walls. Warm throughout — copper, gold and the white at an ember's heart.
 */
@Composable
fun FoundryWorld(palette: JarvisPalette, modifier: Modifier = Modifier, live: Boolean = true, thumbnail: Boolean = false) {
    val clock = rememberWorldTime(live, thumbnail)
    // x, rise speed, phase, sway, size, heat (0 copper … 1 white-gold)
    val embers = remember { Rng(31).let { r -> List(170) { floatArrayOf(r.next(), r.range(0.04f, 0.16f), r.next(), r.range(6f, 40f), r.range(0.8f, 2.6f), r.next()) } } }

    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val w = size.width
        val h = size.height
        val u = designUnit()
        val hair = (1.1f * u).coerceAtLeast(1f)
        val accent = palette.accent
        val hot = palette.highlight
        val deep = palette.secondary
        val floorY = h * 0.84f

        drawRect(Brush.verticalGradient(0f to palette.background, 0.55f to palette.surface.copy(alpha = 0.7f), 1f to Color(0xFF2A0E05)))

        // Hot light from the roof: three soft shafts swaying a few degrees.
        for (i in 0 until 3) {
            val x = w * (0.22f + 0.28f * i) + sin(t * 0.18f + i * 2f) * 30f * u
            val spread = w * 0.16f
            val path = Path().apply {
                moveTo(x - 8f * u, 0f); lineTo(x + 8f * u, 0f)
                lineTo(x + spread + sin(t * 0.2f + i) * 40f * u, floorY); lineTo(x - spread, floorY); close()
            }
            drawPath(path, Brush.verticalGradient(listOf(hot.copy(alpha = 0.10f), Color.Transparent), 0f, floorY))
        }

        // Steel trusswork up both walls: two rails and a zig-zag of braces.
        for (side in 0..1) {
            val rail = if (side == 0) 26f * u + 12f else w - 26f * u - 12f
            val rail2 = rail + (if (side == 0) 1 else -1) * (46f * u + 10f)
            drawLine(accent.copy(alpha = 0.22f), Offset(rail, 0f), Offset(rail, floorY), hair * 1.6f)
            drawLine(accent.copy(alpha = 0.14f), Offset(rail2, 0f), Offset(rail2, floorY), hair)
            val step = 64f * u + 18f
            var y = 0f
            var flip = false
            while (y < floorY) {
                val y2 = (y + step).coerceAtMost(floorY)
                drawLine(accent.copy(alpha = 0.12f), Offset(if (flip) rail else rail2, y), Offset(if (flip) rail2 else rail, y2), hair)
                drawLine(accent.copy(alpha = 0.18f), Offset(rail, y2), Offset(rail2, y2), hair)
                flip = !flip
                y = y2
            }
        }

        // Heat haze: wide translucent bands, wobbling, brighter nearer the floor.
        for (i in 0 until 5) {
            val bandH = 46f * u + 16f
            val y = floorY - (i + 1) * h * 0.11f + sin(t * 0.5f + i * 1.3f) * 7f * u
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, hot.copy(alpha = 0.035f + 0.012f * (5 - i)), Color.Transparent), y - bandH, y + bandH), Offset(0f, y - bandH), Size(w, bandH * 2))
        }

        // The grate: a field of slits, each glowing and flickering on its own.
        drawRect(Brush.verticalGradient(listOf(deep.copy(alpha = 0.55f), Color(0xFF1A0803)), floorY, h), Offset(0f, floorY), Size(w, h - floorY))
        val slit = 11f * u + 5f
        val tick = floor(t * 2.2f).toInt()
        var i = 0
        var x = slit / 2
        while (x < w) {
            val f = 0.35f + 0.65f * StarkGeometry.hash01(i, 7, tick)
            val col = lerp(deep, hot, f)
            drawRect(Brush.verticalGradient(listOf(col.copy(alpha = 0.85f * f), col.copy(alpha = 0.10f)), floorY + 4f, h), Offset(x, floorY + 6f * u + 3f), Size(slit * 0.34f, h - floorY - 10f * u))
            x += slit
            i++
        }
        // Molten horizon: a bloom just above the grate, and a bright seam where floor meets air.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, hot.copy(alpha = 0.34f), accent.copy(alpha = 0.18f)), floorY - h * 0.2f, floorY + 4f), Offset(0f, floorY - h * 0.2f), Size(w, h * 0.2f + 4f))
        drawLine(hot.copy(alpha = 0.9f), Offset(0f, floorY), Offset(w, floorY), hair * 1.6f)

        // Embers: born on the grate, rising and swaying, cooling from white-gold to copper as they climb.
        for (e in embers) {
            val p = (t * e[1] + e[2]) % 1f
            val ex = e[0] * w + sin(t * 0.9f + e[2] * 20f) * e[3] * u
            val ey = floorY - p * (floorY + 20f)
            val fade = sin(p * PI.toFloat()).coerceAtLeast(0f)
            val col = lerp(accent, Color.White, e[5] * (1f - p * 0.6f))
            glowDot(col, Offset(ex, ey), e[4] * u * (1.1f - 0.5f * p), 0.35f + 0.65f * fade)
        }

        cornerBrackets(accent, u, 0.5f)
        vignette(palette.background, 0.85f, 0.5f)
    }
}
