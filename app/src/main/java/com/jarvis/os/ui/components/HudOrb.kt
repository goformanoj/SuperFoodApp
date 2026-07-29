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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.voice.OrbState

/**
 * The JARVIS orb: the theme's artwork, alive.
 *
 * Two earlier versions hand-drew each orb on a Canvas and neither resembled the
 * reference designs, because those are photorealistic renders — volumetric
 * glass, real lighting, thousands of particles — and vector shapes do not reach
 * that however many are added. The artwork IS the orb now, so the resemblance is
 * exact by construction rather than by effort.
 *
 * What a static image cannot do is move or react, so this supplies the rest: the
 * art breathes and swells with the microphone, and [drawOrbOverlay] adds
 * counter-rotating dotted rings, a travelling arc, orbiting dust and flares
 * around it. State tints the artwork — red on error, green while speaking — so
 * it still answers what JARVIS is doing.
 */
@Composable
fun HudOrb(
    modifier: Modifier = Modifier,
    orb: OrbState = OrbState.Idle,
    amplitude: Float = 0f,
    size: Dp = 300.dp,
    palette: JarvisPalette = LocalPalette.current,
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
    val spin by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (busy) 5000 else 13000, easing = LinearEasing)),
        label = "spin",
    )
    val drift by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (busy) 12000 else 34000, easing = LinearEasing)),
        label = "drift",
    )
    val counter by transition.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(if (busy) 9000 else 24000, easing = LinearEasing)),
        label = "counter",
    )
    val breathe by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    val amp = amplitude.coerceIn(0f, 1f)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        // The artwork. It breathes gently and swells with the room.
        val artScale = 0.97f + breathe * 0.022f + amp * 0.05f
        Image(
            painter = painterResource(palette.art),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = artScale
                    scaleY = artScale
                }
                .drawWithContent {
                    drawContent()
                    // A wash of the state colour, and only for the states that
                    // differ from the theme's own accent — Idle and Listening
                    // leave the artwork exactly as drawn.
                    if (orb == OrbState.Error || orb == OrbState.Speaking) {
                        drawRect(color = accent.copy(alpha = 0.28f), blendMode = BlendMode.SrcAtop)
                    }
                },
        )

        // Everything that moves, drawn around the art rather than over it —
        // painting on top would only muddy a render that is already better than
        // anything this can add.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawOrbOverlay(
                palette.orbStyle,
                OrbFrame(
                    // The rings sit outside the art, so they key off a smaller
                    // radius than the box and grow outward from it.
                    radius = this.size.minDimension / 2f * 0.80f,
                    accent = accent,
                    secondary = palette.secondary,
                    highlight = palette.highlight,
                    spin = spin,
                    drift = drift,
                    counter = counter,
                    breathe = breathe,
                    amp = amp,
                ),
            )
        }
    }
}

/**
 * A small orb for the theme picker: the same artwork and the same motion, fixed
 * to one palette, so a card shows what the theme actually looks like.
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
        )
    }
}
