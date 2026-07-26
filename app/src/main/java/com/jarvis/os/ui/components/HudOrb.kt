package com.jarvis.os.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.ElectricBlue
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.ui.theme.WarningOrange
import com.jarvis.os.voice.OrbState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * JARVIS HUD orb — concentric segmented rings, radial tick marks, an orange
 * accent arc and a reactive core. The accent color reflects the [orb] state
 * (Listening/Thinking/Speaking/Error), rings rotate continuously, and
 * [amplitude] (0..1, mic level) drives the glow and inner-ring intensity.
 */
@Composable
fun HudOrb(
    modifier: Modifier = Modifier,
    orb: OrbState = OrbState.Idle,
    amplitude: Float = 0f,
    size: Dp = 300.dp,
) {
    val targetAccent = when (orb) {
        OrbState.Listening -> Cyan
        OrbState.Thinking -> ElectricBlue
        OrbState.Speaking -> SuccessGreen
        OrbState.Error -> ErrorRed
        else -> Cyan.copy(alpha = 0.75f)
    }
    val accent by animateColorAsState(targetAccent, tween(400), label = "accent")

    val transition = rememberInfiniteTransition(label = "hud")
    val spinDuration = if (orb == OrbState.Thinking) 6000 else 14000
    val spin by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(spinDuration, easing = LinearEasing)),
        label = "spin",
    )
    val spin2 by transition.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(22000, easing = LinearEasing)),
        label = "spin2",
    )
    val pulse by transition.animateFloat(
        0.9f, 1.05f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    val amp = amplitude.coerceIn(0f, 1f)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2f

            // Reactive outer glow
            val glowR = r * (0.85f + amp * 0.18f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.18f + amp * 0.35f), Color.Transparent),
                    center = c,
                    radius = glowR,
                ),
                radius = glowR,
                center = c,
            )

            // Radial tick marks
            val ticks = 72
            for (i in 0 until ticks) {
                val a = (i / ticks.toFloat()) * 2f * PI.toFloat()
                val longTick = i % 6 == 0
                val rOut = r * 0.97f
                val rIn = r * (if (longTick) 0.88f else 0.93f)
                val ca = cos(a)
                val sa = sin(a)
                drawLine(
                    color = accent.copy(alpha = if (longTick) 0.85f else 0.35f),
                    start = Offset(c.x + ca * rIn, c.y + sa * rIn),
                    end = Offset(c.x + ca * rOut, c.y + sa * rOut),
                    strokeWidth = if (longTick) 2.5.dp.toPx() else 1.dp.toPx(),
                )
            }

            // Outer ring
            drawCircle(
                color = accent.copy(alpha = 0.5f),
                radius = r * 0.97f,
                center = c,
                style = Stroke(1.5.dp.toPx()),
            )

            // Rotating segmented ring
            val segR = r * 0.78f
            rotate(spin, c) {
                val segs = 14
                val step = 360f / segs
                val sweep = step - 6f
                for (i in 0 until segs) {
                    drawArc(
                        color = accent.copy(alpha = 0.55f),
                        startAngle = i * step,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(c.x - segR, c.y - segR),
                        size = Size(segR * 2f, segR * 2f),
                        style = Stroke(5.dp.toPx()),
                    )
                }
            }

            // Orange accent arcs (JARVIS signature)
            val accR = r * 0.86f
            rotate(spin2, c) {
                drawArc(
                    color = WarningOrange,
                    startAngle = 30f,
                    sweepAngle = 55f,
                    useCenter = false,
                    topLeft = Offset(c.x - accR, c.y - accR),
                    size = Size(accR * 2f, accR * 2f),
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = WarningOrange.copy(alpha = 0.5f),
                    startAngle = 210f,
                    sweepAngle = 22f,
                    useCenter = false,
                    topLeft = Offset(c.x - accR, c.y - accR),
                    size = Size(accR * 2f, accR * 2f),
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Inner reactive ring (thickness follows amplitude)
            val inR = r * 0.6f * pulse
            drawCircle(
                color = ElectricBlue.copy(alpha = 0.6f),
                radius = inR,
                center = c,
                style = Stroke((2f + amp * 6f).dp.toPx()),
            )

            // Inner dashed ring rotating opposite
            val inR2 = r * 0.48f
            rotate(-spin, c) {
                val segs = 24
                val step = 360f / segs
                val sweep = step * 0.5f
                for (i in 0 until segs) {
                    drawArc(
                        color = accent.copy(alpha = 0.4f),
                        startAngle = i * step,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(c.x - inR2, c.y - inR2),
                        size = Size(inR2 * 2f, inR2 * 2f),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }

            // Central core glow
            val coreR = r * 0.34f * (0.95f + amp * 0.25f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.5f),
                        ElectricBlue.copy(alpha = 0.15f),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = coreR * 1.4f,
                ),
                radius = coreR,
                center = c,
            )
        }

        Text(
            text = "J.A.R.V.I.S.",
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
        )
    }
}
