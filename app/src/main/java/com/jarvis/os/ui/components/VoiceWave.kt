package com.jarvis.os.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import kotlin.math.abs
import kotlin.math.sin

/**
 * The waveform strip that sits under the orb in every one of the designs, inside
 * a shallow ellipse.
 *
 * Bars are driven by the live microphone [amplitude] plus a travelling wave, so
 * it idles gently when nothing is being said and leaps when something is. A
 * purely random bar height would look busy while conveying nothing; this one is
 * honest about whether JARVIS can currently hear you.
 */
@Composable
fun VoiceWave(
    /** Mic level as a lambda, so a level change invalidates a draw, not a tree. */
    amplitude: () -> Float,
    modifier: Modifier = Modifier,
    palette: JarvisPalette = LocalPalette.current,
    bars: Int = 56,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        0f, OrbMath.TAU,
        infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "wavePhase",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        // Read inside the draw. In composition it would recompose on every RMS
        // callback from the microphone, which is what this change exists to stop.
        val amp = amplitude().coerceIn(0f, 1f)
        val w = size.width
        val h = size.height
        val midY = h * 0.52f
        val step = w / (bars + 1)

        // The ellipse the bars stand in.
        drawArc(
            color = palette.accent.copy(alpha = 0.35f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(w * 0.04f, midY - h * 0.30f),
            size = androidx.compose.ui.geometry.Size(w * 0.92f, h * 0.72f),
            style = Stroke(1.4f * density),
        )

        for (i in 0 until bars) {
            val x = step * (i + 1)
            // Taper toward the ends so the strip reads as a band, not a block.
            val fromCentre = abs(i - (bars - 1) / 2f) / ((bars - 1) / 2f)
            val taper = 1f - fromCentre * fromCentre * 0.75f
            val travelling = (sin(phase + i * 0.42f) + 1f) / 2f
            val idle = 0.10f + travelling * 0.10f
            val live = amp * (0.35f + travelling * 0.65f)
            val barH = h * 0.40f * taper * (idle + live)

            val color = if (i % 7 == 0) palette.highlight else palette.accent
            drawLine(
                color = color.copy(alpha = 0.45f + amp * 0.45f),
                start = Offset(x, midY - barH),
                end = Offset(x, midY + barH),
                strokeWidth = 2.2f * density,
                cap = StrokeCap.Round,
            )
        }
    }
}
