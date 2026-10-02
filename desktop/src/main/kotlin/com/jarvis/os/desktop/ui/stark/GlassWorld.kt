package com.jarvis.os.desktop.ui.stark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.jarvis.os.ui.theme.JarvisPalette
import kotlin.math.min

/**
 * Glass (Holo's own world): not a scene but a pane. The window itself is transparent (see Main's
 * `transparent` window), so the real Windows desktop — wallpaper, icons, other windows — shows through,
 * and this layer only adds what a holographic overlay would: a faint tint so text stays readable over any
 * wallpaper, a hairline instrument grid, range rings behind the orb, a glowing edge that marks where the pane
 * ends, and bracketed corners.
 *
 * Everything is deliberately thin and low-alpha — heavy fills would defeat the point — and nearly still:
 * one slow sweep. In a thumbnail (the theme picker, which is NOT see-through) it paints a stand-in wallpaper
 * first, so the card shows what the pane looks like over something.
 */
@Composable
fun GlassWorld(palette: JarvisPalette, modifier: Modifier = Modifier, live: Boolean = true, thumbnail: Boolean = false) {
    val clock = rememberWorldTime(live, thumbnail)
    val glass = com.jarvis.os.desktop.ui.LocalGlassLevel.current
    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val w = size.width
        val h = size.height
        val u = designUnit()
        val hair = (1.1f * u).coerceAtLeast(1f)
        val accent = palette.accent
        val c = Offset(w / 2f, if (thumbnail) h * 0.5f else h * 0.43f)
        val base = min(w, h) * 0.5f

        if (thumbnail) standInWallpaper(palette, w, h)

        // The tint: enough to keep light text legible over a bright desktop, light enough to stay see-through.
        val k = glass
        fun tint(lo: Float, hi: Float) = palette.background.copy(alpha = lo + (hi - lo) * k)
        drawRect(Brush.verticalGradient(listOf(tint(0.26f, 0.60f), tint(0.34f, 0.72f), tint(0.42f, 0.84f))))
        // A cool sheen across the top, as on a pane of glass.
        drawRect(Brush.verticalGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), 0f, h * 0.35f), size = Size(w, h * 0.35f))

        // Instrument grid.
        val step = 44f * u + 12f
        var x = 0f
        while (x < w) { drawLine(accent.copy(alpha = 0.045f), Offset(x, 0f), Offset(x, h), 1f); x += step }
        var y = 0f
        while (y < h) { drawLine(accent.copy(alpha = 0.045f), Offset(0f, y), Offset(w, y), 1f); y += step }

        // Range rings behind the orb, and one slow sweep.
        for ((i, f) in listOf(0.55f, 0.82f, 1.12f).withIndex()) drawCircle(accent.copy(alpha = 0.10f + 0.02f * i), base * f, c, style = Stroke(hair))
        rotate(t * 4f, c) {
            for (i in 0 until 72) {
                val long = i % 6 == 0
                drawLine(accent.copy(alpha = if (long) 0.32f else 0.12f), polar(c, i * 5f, base * 1.12f), polar(c, i * 5f, base * (if (long) 1.17f else 1.145f)), hair)
            }
        }

        // The pane's edge: a bright hairline and a soft inner glow, so a see-through window is still a visible object.
        drawRect(accent.copy(alpha = 0.55f), style = Stroke(hair * 1.4f))
        drawRect(Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent), 0f, 26f * u + 8f), size = Size(w, 26f * u + 8f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.12f)), h - 26f * u - 8f, h), Offset(0f, h - 26f * u - 8f), Size(w, 26f * u + 8f))
        cornerBrackets(accent, u, 0.85f)
    }
}

/** What the picker's thumbnail sits "over": a dusk-gradient wallpaper with a sun, so the glass has something to show. */
private fun DrawScope.standInWallpaper(p: JarvisPalette, w: Float, h: Float) {
    drawRect(Brush.linearGradient(listOf(Color(0xFF1F3B73), Color(0xFF6B4E9B), Color(0xFFE08A6B)), Offset(0f, 0f), Offset(w, h)))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE2A8), Color.Transparent), Offset(w * 0.78f, h * 0.30f), h * 0.45f), h * 0.45f, Offset(w * 0.78f, h * 0.30f))
    // A few "desktop icons" and a window edge, to read as a desktop.
    for (i in 0 until 3) drawRect(Color.White.copy(alpha = 0.55f), Offset(w * 0.04f, h * (0.12f + i * 0.14f)), Size(w * 0.05f, h * 0.08f))
    drawRect(Color.White.copy(alpha = 0.18f), Offset(w * 0.08f, h * 0.58f), Size(w * 0.5f, h * 0.34f))
    drawLine(Color.White.copy(alpha = 0.4f), Offset(w * 0.08f, h * 0.64f), Offset(w * 0.58f, h * 0.64f), 1.5f, StrokeCap.Butt)
}
