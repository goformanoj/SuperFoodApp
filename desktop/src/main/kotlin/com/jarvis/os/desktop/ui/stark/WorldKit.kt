package com.jarvis.os.desktop.ui.stark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pieces every desktop world shares: the clock, one design unit, a vignette, bracketed corners.
 *
 * The clock is the important one. A world is ONE Canvas on ONE frame clock, read only inside the
 * draw lambda, so each frame redraws that Canvas and nothing else in the tree. When [live] is
 * false (every screen but Home, and every thumbnail) no clock runs at all and the world is drawn
 * once — behind a scrolling list its redraws would compete with the scroll for the same frame.
 */
@Composable
internal fun rememberWorldTime(live: Boolean, thumbnail: Boolean): MutableFloatState {
    val t = remember { mutableFloatStateOf(if (thumbnail) 6.5f else 3f) }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) t.floatValue += ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
            }
        }
    }
    return t
}

/** One "design pixel": 1 on a ~900px canvas, scaled so thumbnails and big windows both look right. */
internal fun DrawScope.designUnit(): Float = (min(size.width, size.height) / 900f).coerceIn(0.3f, 1.3f)

internal fun polar(c: Offset, deg: Float, radius: Float): Offset {
    val a = (deg - 90f) * (PI.toFloat() / 180f)
    return Offset(c.x + radius * cos(a), c.y + radius * sin(a))
}

/** Darkens the edges so the middle — where the orb and the content are — reads first. */
internal fun DrawScope.vignette(background: Color, strength: Float = 0.82f, centerY: Float = 0.46f) {
    drawRect(
        Brush.radialGradient(
            0.55f to Color.Transparent, 1f to background.copy(alpha = strength),
            center = Offset(size.width / 2, size.height * centerY), radius = max(size.width, size.height) * 0.78f,
        ),
    )
}

/** Bracketed corners: the frame every instrument-style world is hung in. */
internal fun DrawScope.cornerBrackets(color: Color, u: Float, alpha: Float = 0.7f) {
    val inset = 14f * u + 6f
    val len = 64f * u + 16f
    val sw = (1.1f * u).coerceAtLeast(1f) * 1.8f
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color.copy(alpha = alpha), Offset(x, y), Offset(x + dx * len, y), sw, StrokeCap.Square)
        drawLine(color.copy(alpha = alpha), Offset(x, y), Offset(x, y + dy * len), sw, StrokeCap.Square)
    }
    corner(inset, inset, 1f, 1f)
    corner(size.width - inset, inset, -1f, 1f)
    corner(inset, size.height - inset, 1f, -1f)
    corner(size.width - inset, size.height - inset, -1f, -1f)
}

/** A soft glowing dot: a wide faint pass under a small bright one, added rather than blended. */
internal fun DrawScope.glowDot(color: Color, center: Offset, radius: Float, alpha: Float = 1f) {
    drawCircle(color.copy(alpha = 0.22f * alpha), radius * 3.2f, center, blendMode = BlendMode.Plus)
    drawCircle(color.copy(alpha = alpha), radius, center, blendMode = BlendMode.Plus)
}

/** A repeatable pseudo-random stream: the same scene every launch, no generator state to carry. */
internal class Rng(seed: Int) {
    private var s = seed
    fun next(): Float { s = s * 1103515245 + 12345; return ((s ushr 8) and 0xFFFF) / 65536f }
    fun range(a: Float, b: Float) = a + (b - a) * next()
}
