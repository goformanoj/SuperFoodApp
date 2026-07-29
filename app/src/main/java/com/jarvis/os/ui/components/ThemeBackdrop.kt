package com.jarvis.os.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette

/**
 * The space the orb lives in: a wash behind it, a dotted dome overhead, a
 * drifting star field and a perspective ground mesh below.
 *
 * All four are in the reference designs, and their absence is most of why a
 * first pass reads as "an orb on black" rather than as the artwork. Deliberately
 * low-contrast — it has to give depth behind the UI without competing with it.
 */
@Composable
fun ThemeBackdrop(
    modifier: Modifier = Modifier,
    palette: JarvisPalette = LocalPalette.current,
) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    // Very slow. A backdrop that visibly turns is a distraction; one that drifts
    // imperceptibly is atmosphere.
    val drift by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(200_000, easing = LinearEasing)),
        label = "backdropDrift",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val focus = Offset(w / 2f, h * 0.34f)

        // Wash from behind the orb.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.accent.copy(alpha = 0.11f),
                    palette.secondary.copy(alpha = 0.055f),
                    Color.Transparent,
                ),
                center = focus,
                radius = maxOf(w, h) * 0.78f,
            ),
        )

        // Star field, deterministic so it drifts rather than flickers.
        for (i in 0 until 80) {
            val x = OrbMath.unitRandom(i * 3 + 1) * w
            val y = OrbMath.unitRandom(i * 3 + 2) * h
            val warm = OrbMath.unitRandom(i * 13 + 5) > 0.74f
            val bright = OrbMath.unitRandom(i * 17 + 7) > 0.94f
            val alpha = OrbMath.range(i * 7 + 11, 0.10f, 0.50f)
            if (bright) {
                // A few real stars with flares, as in the Nebula design.
                flare(Offset(x, y), OrbMath.range(i, 3f, 7f) * density, Color.White, alpha * 1.4f)
            } else {
                drawCircle(
                    color = (if (warm) palette.highlight else Color.White).copy(alpha = alpha),
                    radius = OrbMath.range(i * 3 + 3, 0.6f, 1.9f) * density,
                    center = Offset(x, y),
                )
            }
        }

        // The dome overhead: dotted latitude arcs, turning almost imperceptibly.
        val dotted = PathEffect.dashPathEffect(floatArrayOf(1.6f * density, 5f * density))
        rotate(drift * 0.12f, focus) {
            listOf(0.52f, 0.68f, 0.86f, 1.05f).forEachIndexed { i, frac ->
                val rad = maxOf(w, h) * frac
                drawOval(
                    color = palette.secondary.copy(alpha = 0.10f - i * 0.018f),
                    topLeft = Offset(focus.x - rad, focus.y - rad * 0.86f),
                    size = Size(rad * 2f, rad * 1.72f),
                    style = Stroke(1f * density, pathEffect = dotted),
                )
            }
        }

        // Ground mesh in the lower part, receding to a horizon behind the orb.
        groundMesh(
            horizonY = h * 0.62f,
            color = palette.secondary,
            rows = 10,
            columns = 16,
            alpha = 0.16f,
        )
    }
}
