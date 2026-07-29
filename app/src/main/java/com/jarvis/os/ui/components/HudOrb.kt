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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.voice.OrbState
import kotlin.math.roundToInt

/**
 * The JARVIS orb: the theme's own artwork, with its own rings turning.
 *
 * The path here was long and worth recording. Two versions hand-drew each orb
 * from vector shapes and neither resembled the references, which are
 * photorealistic renders. A third shipped the renders but bolted rotating rings
 * on OUTSIDE them, which the user rejected in one line — "the objects in the
 * image should move instead of you adding extra moving elements outside".
 *
 * That is what happens now. The artwork is clipped into concentric bands and
 * each band is rotated at its own speed, so the design's real rings turn against
 * each other. Nothing is drawn over the art and nothing orbits around it.
 *
 * The wordmark had to come out of the images for this to work at all — sliced
 * and spun, baked text would smear — so it is redrawn in code, centred, which
 * also fixes it being clipped by the circular fade.
 */
@Composable
fun HudOrb(
    modifier: Modifier = Modifier,
    orb: OrbState = OrbState.Idle,
    amplitude: Float = 0f,
    size: Dp = 300.dp,
    palette: JarvisPalette = LocalPalette.current,
    showLabel: Boolean = true,
) {
    val targetAccent = when (orb) {
        OrbState.Listening -> palette.accent
        OrbState.Thinking -> palette.secondary
        OrbState.Speaking -> SuccessGreen
        OrbState.Error -> ErrorRed
        else -> palette.accent.copy(alpha = 0.75f)
    }
    val accent by animateColorAsState(targetAccent, tween(400), label = "accent")

    val transition = rememberInfiniteTransition(label = "orb")
    val busy = orb == OrbState.Thinking
    // One master clock. Each band multiplies it, so the rings keep a fixed
    // relationship to each other however fast the whole thing is running.
    val drift by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (busy) 9000 else 26000, easing = LinearEasing)),
        label = "drift",
    )
    val breathe by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    val amp = amplitude.coerceIn(0f, 1f)
    val art = ImageBitmap.imageResource(palette.art)
    val bands = bandsFor(palette.orbStyle)
    val tint = when (orb) {
        OrbState.Error, OrbState.Speaking -> accent
        else -> null
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            // The art breathes and swells with the room.
            val scale = 0.99f + breathe * 0.018f + amp * 0.045f

            drawOrbBloom(
                OrbFrame(
                    radius = r, accent = accent, secondary = palette.secondary,
                    highlight = palette.highlight, spin = drift, drift = drift,
                    counter = -drift, breathe = breathe, amp = amp,
                ),
            )

            bands.forEach { band ->
                clipPath(annulus(center, r * band.inner, r * band.outer)) {
                    rotate(drift * band.speed, center) {
                        drawArt(art, center, r * scale, tint)
                    }
                }
            }
        }

        if (showLabel) {
            JarvisWordmark(palette = palette, scale = (size / 280.dp).coerceIn(0.30f, 1.15f))
        }
    }
}

/** A ring-shaped clip: the outer disc with the inner one punched out. */
private fun annulus(centre: Offset, inner: Float, outer: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    addOval(Rect(centre, outer))
    if (inner > 0f) addOval(Rect(centre, inner))
}

/** Draws the artwork centred on [centre] at [radius], optionally state-tinted. */
private fun DrawScope.drawArt(art: ImageBitmap, centre: Offset, radius: Float, tint: Color?) {
    val side = (radius * 2f).roundToInt()
    drawImage(
        image = art,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(art.width, art.height),
        dstOffset = IntOffset((centre.x - radius).roundToInt(), (centre.y - radius).roundToInt()),
        dstSize = IntSize(side, side),
        colorFilter = tint?.let { ColorFilter.tint(it.copy(alpha = 0.35f), BlendMode.SrcAtop) },
    )
}

/**
 * A small orb for the theme picker: the same artwork and the same turning rings,
 * fixed to one palette. The wordmark is dropped — at this size it would be an
 * illegible smear across the design the card exists to show.
 */
@Composable
fun OrbPreview(palette: JarvisPalette, size: Dp = 92.dp, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalPalette provides palette) {
        HudOrb(
            modifier = modifier,
            orb = OrbState.Listening,
            amplitude = 0f,
            size = size,
            palette = palette,
            showLabel = false,
        )
    }
}
