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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette

/**
 * The layer behind everything: a vignette, a drifting star/dust field and the
 * faint arc of a dome overhead.
 *
 * Every one of the designs has this — the orb never sits on flat black, it sits
 * inside a space. Deliberately low-contrast: it has to read as depth behind the
 * UI, not compete with it.
 */
@Composable
fun ThemeBackdrop(
    modifier: Modifier = Modifier,
    palette: JarvisPalette = LocalPalette.current,
) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    // Very slow: a backdrop that visibly turns is a distraction, one that drifts
    // imperceptibly is atmosphere.
    val drift by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(180_000, easing = LinearEasing)),
        label = "backdropDrift",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Radial wash from behind the orb, which sits in the upper third.
        val focus = Offset(w / 2f, h * 0.34f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.accent.copy(alpha = 0.10f),
                    palette.secondary.copy(alpha = 0.05f),
                    Color.Transparent,
                ),
                center = focus,
                radius = maxOf(w, h) * 0.75f,
            ),
        )

        // Dust field. Deterministic placement, so it drifts rather than flickers.
        val stars = 70
        for (i in 0 until stars) {
            val x = OrbMath.unitRandom(i * 3 + 1) * w
            val y = OrbMath.unitRandom(i * 3 + 2) * h
            val r = OrbMath.range(i * 3 + 3, 0.6f, 2.0f) * density
            val alpha = OrbMath.range(i * 7 + 11, 0.10f, 0.45f)
            val warm = OrbMath.unitRandom(i * 13 + 5) > 0.72f
            drawCircle(
                color = (if (warm) palette.highlight else Color.White).copy(alpha = alpha),
                radius = r,
                center = Offset(x, y),
            )
        }

        // The dome overhead: concentric arcs, turning almost imperceptibly.
        rotate(drift * 0.15f, focus) {
            listOf(0.55f, 0.75f, 0.95f).forEachIndexed { i, frac ->
                val rad = maxOf(w, h) * frac
                drawCircle(
                    color = palette.secondary.copy(alpha = 0.09f - i * 0.02f),
                    radius = rad,
                    center = focus,
                    style = Stroke(1f * density),
                )
            }
        }
    }
}
